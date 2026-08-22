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
   * Groups every reactor module's own built jar by its {@link #effectiveTenant} into a {@code kind:
   * ArtifactSet} document -- plain string-building, not {@code ArtifactSetManifestParser}'s model
   * types: generating the file is simple enough on its own, and this Mojo has no reason to depend
   * on {@code gimle-module}'s parser package just to serialize what it already knows structurally.
   */
  static String generateManifestYaml(List<MavenProject> reactorProjects, String defaultTenantId) {
    Map<String, List<String>> byTenant = new LinkedHashMap<>();
    List<String> untenanted = new ArrayList<>();
    for (MavenProject reactorProject : reactorProjects) {
      String jarPath =
          Path.of(
                  reactorProject.getBuild().getDirectory(),
                  reactorProject.getBuild().getFinalName() + ".jar")
              .toString();
      String tenant = effectiveTenant(reactorProject, defaultTenantId);
      if (tenant == null) {
        untenanted.add(jarPath);
      } else {
        byTenant.computeIfAbsent(tenant, k -> new ArrayList<>()).add(jarPath);
      }
    }

    StringBuilder yaml = new StringBuilder("kind: ArtifactSet\n");
    if (!byTenant.isEmpty()) {
      yaml.append("tenant:\n");
      for (Map.Entry<String, List<String>> entry : byTenant.entrySet()) {
        yaml.append("  ").append(entry.getKey()).append(":\n");
        for (String jarPath : entry.getValue()) {
          yaml.append("    - ").append(jarPath).append('\n');
        }
      }
    }
    if (!untenanted.isEmpty()) {
      yaml.append("modules:\n");
      for (String jarPath : untenanted) {
        yaml.append("  - ").append(jarPath).append('\n');
      }
    }
    return yaml.toString();
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
