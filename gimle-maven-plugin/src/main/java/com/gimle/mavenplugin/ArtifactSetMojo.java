package com.gimle.mavenplugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * {@code mvn gimle:artifactset-push} -- the multi-module answer to {@link PublishMojo}: walks every
 * module already in the current reactor, generates a {@code kind: ArtifactSet} manifest grouping
 * their built jars by tenant, and shells out to a real {@code GimleCli apply} the same way {@link
 * PublishMojo} shells out to {@code artifact push}, reusing 100% of that command's
 * pre-flight/push/report logic rather than a second implementation living in this plugin.
 *
 * <p>Extends {@link AbstractGimleRootMojo} rather than {@link AbstractGimleMojo}: this goal reads
 * the whole reactor's own module list, so it must run exactly once per reactor invocation
 * regardless of how many modules it's bound in, not once per module the way a per-module goal
 * would.
 */
@Mojo(name = "artifactset-push", threadSafe = true)
public final class ArtifactSetMojo extends AbstractGimleRootMojo {

  @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
  private List<MavenProject> reactorProjects;

  /**
   * The default tenant for every reactor module that doesn't name its own via the {@code
   * gimle.artifactset.tenantId} property in its own {@code pom.xml} -- see {@link
   * #effectiveTenant}. Absent (the default) means untenanted.
   */
  @Parameter(property = "gimle.artifactset.tenantId")
  private String tenantId;

  @Parameter(property = "gimle.artifactset.server", defaultValue = "127.0.0.1:8080")
  private String server;

  /** Overridable so an out-of-tree project can pin a CLI build other than this plugin's own. */
  @Parameter(property = "gimle.artifactset.cliVersion", defaultValue = "${plugin.version}")
  private String cliVersion;

  @Parameter(
      defaultValue = "${project.remoteProjectRepositories}",
      readonly = true,
      required = true)
  private List<RemoteRepository> remoteRepositories;

  @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
  private RepositorySystemSession repositorySystemSession;

  @Component private RepositorySystem repositorySystem;

  @Override
  protected void executeAtRoot() throws MojoExecutionException, MojoFailureException {
    Path manifest = generateManifest();
    String cliClasspath =
        GimleProcesses.resolveRuntimeClasspath(
            "gimle-cli", cliVersion, remoteRepositories, repositorySystemSession, repositorySystem);

    List<String> command = new ArrayList<>();
    command.add(GimleProcesses.javaExecutable());
    command.add("-cp");
    command.add(cliClasspath);
    command.add("com.gimle.cli.GimleCli");
    command.add("apply");
    command.add("-f");
    command.add(manifest.toString());
    command.add("--server");
    command.add(server);

    getLog()
        .info(
            "publishing "
                + reactorProjects.size()
                + " reactor module(s) as one ArtifactSet to the registry behind "
                + server);
    GimleProcesses.runAndWait(command, getLog());
  }

  /**
   * Writes {@link #generateManifestYaml}'s output into this (root) project's own {@code target/} as
   * {@code artifactset.yaml} -- the one part of manifest generation that actually needs a live
   * Maven session, kept as thin as possible so the content-building logic itself stays a pure
   * function {@code ArtifactSetMojoTest} can assert on directly.
   */
  private Path generateManifest() throws MojoExecutionException {
    String yaml = generateManifestYaml(reactorProjects, tenantId);
    try {
      Path outputDir = Path.of(project.getBuild().getDirectory());
      Files.createDirectories(outputDir);
      Path manifestFile = outputDir.resolve("artifactset.yaml");
      Files.writeString(manifestFile, yaml);
      return manifestFile;
    } catch (IOException e) {
      throw new MojoExecutionException("failed to write generated artifactset.yaml", e);
    }
  }

  /**
   * Groups every reactor module's own built artifact by its {@link #effectiveTenant} into a {@code
   * kind: ArtifactSet} document -- plain string-building, not {@code ArtifactSetManifestParser}'s
   * model types: generating the file is simple enough on its own, and this Mojo has no reason to
   * depend on {@code gimle-module}'s parser package just to serialize what it already knows
   * structurally.
   *
   * <p>A reactor module is an ordinary module jar (a bare path entry) unless its own {@code
   * pom.xml} says otherwise via {@code gimle.artifactset.kind} ({@code vessel} or {@code bundle}),
   * the same per-module-property override shape {@code gimle.artifactset.tenantId} already uses. A
   * {@code vessel}/{@code bundle} module's coordinate defaults to {@code
   * {groupId}.{artifactId}}/{@code {project.version}} (overridable via {@code
   * gimle.artifactset.name}/{@code .version}); a bundle additionally requires {@code
   * gimle.artifactset.command} (comma-separated argv) and usually {@code
   * gimle.artifactset.artifact} (its build-output directory, relative to the module's own base
   * directory -- a Quarkus fast-jar build outputs {@code target/quarkus-app}, which this Mojo has
   * no way to guess). A misconfigured module fails manifest generation here with a Mojo error
   * naming it, never later as a confusing CLI parse error against a generated file.
   */
  static String generateManifestYaml(List<MavenProject> reactorProjects, String defaultTenantId)
      throws MojoExecutionException {
    Map<String, List<List<String>>> byTenant = new LinkedHashMap<>();
    List<List<String>> untenanted = new ArrayList<>();
    for (MavenProject reactorProject : reactorProjects) {
      // A pom-packaged project builds no {finalName}.jar, so the default module-jar entry shape
      // has nothing to push for it -- an aggregator root would otherwise fail the whole set on
      // its own nonexistent jar. An explicit gimle.artifactset.kind still opts one in: a
      // pom-packaged submodule is a legitimate way to wrap a vessel jar or bundle directory
      // produced by other means.
      if ("pom".equals(reactorProject.getPackaging())
          && property(reactorProject, "gimle.artifactset.kind", null) == null) {
        continue;
      }
      List<String> entry = entryLines(reactorProject);
      String tenant = effectiveTenant(reactorProject, defaultTenantId);
      if (tenant == null) {
        untenanted.add(entry);
      } else {
        byTenant.computeIfAbsent(tenant, k -> new ArrayList<>()).add(entry);
      }
    }
    if (byTenant.isEmpty() && untenanted.isEmpty()) {
      throw new MojoExecutionException(
          "no reactor module produces an ArtifactSet entry -- every project in this reactor is"
              + " pom-packaged with no gimle.artifactset.kind declared; run the goal over a"
              + " reactor that builds at least one artifact");
    }

    StringBuilder yaml = new StringBuilder("kind: ArtifactSet\n");
    if (!byTenant.isEmpty()) {
      yaml.append("tenant:\n");
      for (Map.Entry<String, List<List<String>>> entry : byTenant.entrySet()) {
        yaml.append("  ").append(entry.getKey()).append(":\n");
        for (List<String> lines : entry.getValue()) {
          appendListEntry(yaml, lines, "    ");
        }
      }
    }
    if (!untenanted.isEmpty()) {
      yaml.append("modules:\n");
      for (List<String> lines : untenanted) {
        appendListEntry(yaml, lines, "  ");
      }
    }
    return yaml.toString();
  }

  private static void appendListEntry(StringBuilder yaml, List<String> lines, String indent) {
    yaml.append(indent).append("- ").append(lines.get(0)).append('\n');
    for (int i = 1; i < lines.size(); i++) {
      yaml.append(indent).append("  ").append(lines.get(i)).append('\n');
    }
  }

  private static List<String> entryLines(MavenProject reactorProject)
      throws MojoExecutionException {
    String kind = property(reactorProject, "gimle.artifactset.kind", "module");
    String artifactOverride = property(reactorProject, "gimle.artifactset.artifact", null);
    String artifactPath =
        artifactOverride != null
            ? reactorProject.getBasedir().toPath().resolve(artifactOverride).toString()
            : Path.of(
                    reactorProject.getBuild().getDirectory(),
                    reactorProject.getBuild().getFinalName() + ".jar")
                .toString();
    String command = property(reactorProject, "gimle.artifactset.command", null);
    String workdir = property(reactorProject, "gimle.artifactset.workdir", null);
    return switch (kind) {
      case "module" -> {
        requireAbsent(reactorProject, "module", command, "gimle.artifactset.command");
        requireAbsent(reactorProject, "module", workdir, "gimle.artifactset.workdir");
        yield List.of(artifactPath);
      }
      case "vessel" -> {
        requireAbsent(reactorProject, "vessel", command, "gimle.artifactset.command");
        requireAbsent(reactorProject, "vessel", workdir, "gimle.artifactset.workdir");
        yield coordinateLines(reactorProject, artifactPath, "vessel");
      }
      case "bundle" -> {
        if (command == null || command.isBlank()) {
          throw new MojoExecutionException(
              reactorProject.getArtifactId()
                  + " declares gimle.artifactset.kind=bundle but no gimle.artifactset.command --"
                  + " a bundle needs an entrypoint");
        }
        List<String> lines =
            new ArrayList<>(coordinateLines(reactorProject, artifactPath, "bundle"));
        lines.add("command: [" + quotedCommandList(command) + "]");
        if (workdir != null && !workdir.isBlank()) {
          lines.add("workdir: " + singleQuoted(workdir));
        }
        yield List.copyOf(lines);
      }
      default ->
          throw new MojoExecutionException(
              reactorProject.getArtifactId()
                  + " declares unknown gimle.artifactset.kind '"
                  + kind
                  + "' -- expected module, vessel, or bundle");
    };
  }

  private static List<String> coordinateLines(
      MavenProject reactorProject, String artifactPath, String kind) {
    String name =
        property(
            reactorProject,
            "gimle.artifactset.name",
            reactorProject.getGroupId() + "." + reactorProject.getArtifactId());
    String version =
        property(reactorProject, "gimle.artifactset.version", reactorProject.getVersion());
    return List.of(
        "artifact: " + artifactPath, "kind: " + kind, "name: " + name, "version: " + version);
  }

  private static String quotedCommandList(String commaSeparated) {
    List<String> quoted = new ArrayList<>();
    for (String part : commaSeparated.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        quoted.add(singleQuoted(trimmed));
      }
    }
    return String.join(", ", quoted);
  }

  private static String singleQuoted(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private static void requireAbsent(
      MavenProject reactorProject, String kind, String value, String propertyName)
      throws MojoExecutionException {
    if (value != null && !value.isBlank()) {
      throw new MojoExecutionException(
          reactorProject.getArtifactId()
              + " declares "
              + propertyName
              + " but gimle.artifactset.kind="
              + kind
              + " -- that property only applies to a bundle");
    }
  }

  private static String property(
      MavenProject reactorProject, String propertyName, String defaultValue) {
    String value = reactorProject.getProperties().getProperty(propertyName);
    return value != null && !value.isBlank() ? value : defaultValue;
  }

  /**
   * A submodule's own {@code gimle.artifactset.tenantId} property in its own {@code pom.xml} wins
   * over the reactor-wide {@code defaultTenantId} -- ordinary Maven properties inheritance, nothing
   * plugin-specific, the same override shape most Maven plugin settings already follow. {@code
   * null} means untenanted.
   */
  static String effectiveTenant(MavenProject reactorProject, String defaultTenantId) {
    String own = reactorProject.getProperties().getProperty("gimle.artifactset.tenantId");
    return own != null && !own.isBlank() ? own : defaultTenantId;
  }
}
