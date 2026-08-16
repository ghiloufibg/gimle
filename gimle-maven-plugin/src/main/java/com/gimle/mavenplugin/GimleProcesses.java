package com.gimle.mavenplugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;

/**
 * Small process/classpath-resolution mechanics shared by more than one {@code gimle:*} goal --
 * previously duplicated between {@link AbstractGimleMojo} and {@link DocsMojo} ({@code
 * javaExecutable()}) and unique to {@link AgentMojo} ({@code resolveWorkerClasspath()}, generalized
 * here to any artifactId so {@link BootstrapMojo} can reuse it for the six modules it spawns).
 */
final class GimleProcesses {

  private GimleProcesses() {}

  /**
   * Resolves the real java launcher this Maven process itself is running under, mirroring {@code
   * gimle-agent}'s own {@code ResourceLimitEnforcementTest.javaExecutable()} -- so a spawned child
   * runs on the exact same JDK the developer already has active for this build.
   */
  static String javaExecutable() {
    Optional<String> command = ProcessHandle.current().info().command();
    if (command.isPresent()) {
      return command.get();
    }
    Path javaBin = Path.of(System.getProperty("java.home"), "bin");
    for (String candidate : List.of("java", "java.exe")) {
      Path path = javaBin.resolve(candidate);
      if (Files.isRegularFile(path)) {
        return path.toString();
      }
    }
    throw new IllegalStateException("could not locate the java launcher under " + javaBin);
  }

  /**
   * Resolves the {@code mvn}/{@code mvn.cmd} launcher of the Maven distribution this process is
   * already running under, mirroring {@link #javaExecutable()}'s "spawn on the exact same install
   * the developer already has active" resolution style. The {@code mvn} launcher script passes
   * {@code -Dmaven.home} on its own {@code java} command line, so that property is the most direct
   * record of which install is running; {@code MAVEN_HOME} and a {@code PATH} scan are
   * progressively weaker guesses, and the bare script name is a last resort that leaves resolution
   * to {@link ProcessBuilder} at spawn time (e.g. under an IDE-embedded Maven with none of the
   * above set).
   */
  static String mavenExecutable() {
    for (String home : List.of(propertyOrEmpty("maven.home"), envOrEmpty("MAVEN_HOME"))) {
      Optional<Path> launcher = mavenLauncherUnder(home);
      if (launcher.isPresent()) {
        return launcher.get().toString();
      }
    }
    Optional<Path> onPath = mavenLauncherOnPath(envOrEmpty("PATH"));
    if (onPath.isPresent()) {
      return onPath.get().toString();
    }
    return isWindows() ? "mvn.cmd" : "mvn";
  }

  static Optional<Path> mavenLauncherUnder(String mavenHome) {
    if (mavenHome == null || mavenHome.isBlank()) {
      return Optional.empty();
    }
    return firstLauncherIn(Path.of(mavenHome, "bin"));
  }

  static Optional<Path> mavenLauncherOnPath(String pathValue) {
    if (pathValue == null || pathValue.isBlank()) {
      return Optional.empty();
    }
    for (String dir : pathValue.split(File.pathSeparator)) {
      if (dir.isBlank()) {
        continue;
      }
      Optional<Path> launcher = firstLauncherIn(Path.of(dir));
      if (launcher.isPresent()) {
        return launcher;
      }
    }
    return Optional.empty();
  }

  private static Optional<Path> firstLauncherIn(Path directory) {
    for (String candidate : List.of("mvn", "mvn.cmd")) {
      Path path = directory.resolve(candidate);
      if (Files.isRegularFile(path)) {
        return Optional.of(path);
      }
    }
    return Optional.empty();
  }

  private static String propertyOrEmpty(String name) {
    String value = System.getProperty(name);
    return value == null ? "" : value;
  }

  private static String envOrEmpty(String name) {
    String value = System.getenv(name);
    return value == null ? "" : value;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }

  /**
   * Resolves {@code artifactId}'s runtime classpath directly against its already-{@code mvn
   * install}ed jar via Maven's own dependency resolver, independent of reactor build order --
   * {@link AgentMojo}'s original {@code resolveWorkerClasspath()}, generalized to any module rather
   * than hardcoding {@code gimle-worker}.
   */
  static String resolveRuntimeClasspath(
      String artifactId,
      String projectVersion,
      List<RemoteRepository> remoteRepositories,
      RepositorySystemSession repositorySystemSession,
      RepositorySystem repositorySystem)
      throws MojoExecutionException {
    Artifact artifact = new DefaultArtifact("com.gimle", artifactId, "jar", projectVersion);
    CollectRequest collectRequest = new CollectRequest();
    collectRequest.setRoot(new Dependency(artifact, "runtime"));
    collectRequest.setRepositories(remoteRepositories);
    DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);

    List<ArtifactResult> results;
    try {
      results =
          repositorySystem
              .resolveDependencies(repositorySystemSession, dependencyRequest)
              .getArtifactResults();
    } catch (DependencyResolutionException e) {
      throw new MojoExecutionException(
          "failed to resolve "
              + artifactId
              + "'s runtime classpath -- has `mvn install` been run"
              + " yet?",
          e);
    }

    List<String> paths = new ArrayList<>();
    for (ArtifactResult result : results) {
      paths.add(result.getArtifact().getFile().getAbsolutePath());
    }
    return String.join(File.pathSeparator, paths);
  }

  /**
   * Spawns {@code command} as a real OS process with inherited I/O and waits for it to exit,
   * failing the Mojo on a non-zero exit code -- the shared shape {@link PublishMojo} originally
   * carried inline, generalized here once a second goal ({@link DoctorMojo}/{@link InitMojo})
   * needed the identical logic rather than a third copy of it.
   */
  static void runAndWait(List<String> command, Log log)
      throws MojoExecutionException, MojoFailureException {
    log.info("running: " + String.join(" ", command));
    Process process;
    try {
      process = new ProcessBuilder(command).inheritIO().start();
    } catch (IOException e) {
      throw new MojoExecutionException("failed to start " + command.get(0), e);
    }
    try {
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new MojoFailureException(command.get(0) + " exited with code " + exitCode);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroy();
    }
  }

  /**
   * Like {@link #runAndWait(List, Log)} but with a shutdown hook destroying the child while it
   * runs, for long-lived foreground children (a server the developer stops with Ctrl+C, a nested
   * build): a backup for whatever signal propagation the OS doesn't already handle on its own (a
   * plain Ctrl+C in a Windows console typically reaches both this process and the child directly)
   * -- belt-and-suspenders, not the only path to a clean shutdown. Fails the Mojo on a non-zero
   * exit code, naming {@code label} in the failure.
   */
  static void runAndWaitWithShutdownHook(List<String> command, String label)
      throws MojoExecutionException, MojoFailureException {
    int exitCode = startAndAwaitExit(command, null, label);
    // An interrupted wait (flag restored by awaitExit) is a deliberate stop, not a child failure.
    if (exitCode != 0 && !Thread.currentThread().isInterrupted()) {
      throw new MojoFailureException(label + " exited with code " + exitCode);
    }
  }

  /**
   * Spawns {@code command} with inherited I/O (from {@code workingDirectory} if non-null) under the
   * same shutdown-hook cover as {@link #runAndWaitWithShutdownHook(List, String)}, but returns the
   * child's exit code instead of failing on it -- for callers with work left to do after a failed
   * child (report sweeps, result publication) before deciding the build's own fate. Unlike {@link
   * #runAndWait(List, Log)} this logs nothing itself -- each caller already announces the launch in
   * its own words.
   */
  static int startAndAwaitExit(List<String> command, Path workingDirectory, String label)
      throws MojoExecutionException {
    Process process;
    try {
      ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
      if (workingDirectory != null) {
        builder.directory(workingDirectory.toFile());
      }
      process = builder.start();
    } catch (IOException e) {
      throw new MojoExecutionException("failed to start " + command.get(0), e);
    }
    Thread shutdownHook = new Thread(process::destroy, "gimle-mojo-shutdown-" + label);
    Runtime.getRuntime().addShutdownHook(shutdownHook);
    try {
      return awaitExit(process);
    } finally {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException ignored) {
        // The JVM is already shutting down -- the hook either already ran or is redundant now.
      }
    }
  }

  /**
   * Waits for {@code process}, translating an interrupt into "destroy the child and report failure"
   * (-1) rather than letting the checked {@link InterruptedException} escape.
   */
  static int awaitExit(Process process) {
    try {
      return process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroy();
      return -1;
    }
  }
}
