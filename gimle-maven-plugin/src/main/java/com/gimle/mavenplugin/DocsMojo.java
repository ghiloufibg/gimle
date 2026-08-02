package com.gimle.mavenplugin;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * {@code mvn gimle:docs} -- runs the developer documentation site's full build pipeline in one
 * command: aggregate Javadoc across the platform modules, copy it into {@code gimle-docs}'s own
 * {@code static/javadoc/}, then build the Docusaurus site. Unlike {@link ControlPlaneMojo}/{@link
 * AgentMojo}/{@link DeployMojo}, this doesn't extend {@link AbstractGimleMojo}: its job is a fixed
 * 3-step pipeline (spawn {@code mvn javadoc:aggregate}, copy a directory, spawn two {@code bun}
 * commands), not "spawn one subprocess and wait." A direct goal invocation with no bound {@code
 * <phase>} still iterates the whole reactor by default (see {@link AbstractGimleMojo}'s own
 * javadoc), so this self-filters to the root aggregator project itself (artifactId {@code "gimle"})
 * rather than one leaf module -- the root project is always present in any reactor build regardless
 * of {@code -pl}/profile flags, which guarantees exactly one execution.
 *
 * <p>See {@code claudedocs/docs-site-design.md} and {@code gimle-docs/pom.xml}'s own description
 * for why this exists as a separate command rather than folding into {@code mvn verify -P docs}:
 * {@code javadoc:aggregate} is deliberately never bound to a lifecycle phase (see the root pom's
 * {@code maven-javadoc-plugin} {@code pluginManagement} entry), so nothing else chains it before
 * {@code gimle-docs}'s own build runs.
 */
@Mojo(name = "docs")
public final class DocsMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (!"gimle".equals(project.getArtifactId())) {
      getLog().debug("skipping docs: not the root aggregator project");
      return;
    }

    Path root = project.getBasedir().toPath();
    Path docsDir = root.resolve("gimle-docs");
    Path javadocSource = root.resolve("target").resolve("reports").resolve("apidocs");
    Path javadocDest = docsDir.resolve("static").resolve("javadoc");

    getLog().info("running: mvn javadoc:aggregate");
    runAndWait(root, List.of(mavenExecutable(), "javadoc:aggregate"));

    getLog().info("copying " + javadocSource + " to " + javadocDest);
    copyJavadocOutput(javadocSource, javadocDest);

    getLog().info("running: bun install");
    runAndWait(docsDir, List.of("bun", "install", "--frozen-lockfile"));

    getLog().info("running: bun run build");
    runAndWait(docsDir, List.of("bun", "run", "build"));
  }

  /**
   * Recursively replaces {@code dest} with the contents of {@code source}, clearing any stale files
   * from a previous run's include list first. No-ops (with a warning, not a failure) if {@code
   * source} doesn't exist -- matching {@code gimle-docs/pom.xml}'s own copy-javadoc-static
   * execution, which tolerates aggregate Javadoc never having been generated.
   */
  private void copyJavadocOutput(Path source, Path dest) throws MojoExecutionException {
    if (!Files.isDirectory(source)) {
      getLog()
          .warn(
              source
                  + " does not exist -- run 'mvn javadoc:aggregate' first, or expect a docs site"
                  + " with no API Reference content");
      return;
    }
    try {
      deleteRecursively(dest);
      Files.createDirectories(dest);
      Files.walkFileTree(
          source,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
              Files.createDirectories(dest.resolve(source.relativize(dir)));
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.copy(file, dest.resolve(source.relativize(file)));
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      throw new MojoExecutionException("failed to copy javadoc output to " + dest, e);
    }
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      List<Path> ordered = new ArrayList<>(paths.toList());
      ordered.sort(Comparator.reverseOrder());
      for (Path path : ordered) {
        Files.delete(path);
      }
    }
  }

  private void runAndWait(Path workingDirectory, List<String> command)
      throws MojoExecutionException, MojoFailureException {
    Process process;
    try {
      process =
          new ProcessBuilder(command).directory(workingDirectory.toFile()).inheritIO().start();
    } catch (IOException e) {
      throw new MojoExecutionException("failed to start " + command.get(0), e);
    }
    try {
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new MojoFailureException(String.join(" ", command) + " exited with code " + exitCode);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroy();
    }
  }

  /**
   * Resolves the {@code mvn}/{@code mvn.cmd} launcher of the Maven distribution this process is
   * already running under (via {@code maven.home}, set by the {@code mvn}/{@code mvn.cmd} launcher
   * script itself), mirroring {@link AbstractGimleMojo#javaExecutable()}'s "run under the exact
   * same install this process already uses" resolution style, but for {@code mvn} instead of {@code
   * java}. Falls back to the bare command name (resolved off {@code PATH}) if {@code maven.home}
   * isn't set, e.g. under an IDE-embedded Maven distribution not launched via the script.
   */
  private static String mavenExecutable() {
    Optional<String> mavenHome = Optional.ofNullable(System.getProperty("maven.home"));
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    String script = windows ? "mvn.cmd" : "mvn";
    if (mavenHome.isPresent()) {
      Path candidate = Path.of(mavenHome.get(), "bin", script);
      if (Files.isRegularFile(candidate)) {
        return candidate.toString();
      }
    }
    return script;
  }
}
