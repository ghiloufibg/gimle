package com.gimle.mavenplugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Shared mechanics for every {@code gimle:*} goal. Each one only makes sense against one specific
 * reactor module (e.g. {@code controlplane} only means something inside {@code
 * gimle-controlplane}), but a direct goal invocation with no bound {@code <phase>} iterates the
 * whole reactor -- there is no built-in way to target just one module without {@code -pl}. So
 * {@link #execute()} no-ops for every project except the one {@link #targetArtifactId()} names, and
 * only that module's own execution actually spawns a process.
 *
 * <p>Every goal spawns a genuinely separate OS process (never runs the target's {@code main()} via
 * reflection in this same JVM): {@code AgentMain}, {@code ControlPlaneMain}, and {@code GimleCli}
 * all call {@code System.exit()} on error paths, which would tear down this Maven process too if
 * run in-process -- the same real-subprocess model {@code AgentMain}/{@code
 * WorkerProcessSupervisor} already use elsewhere in this codebase.
 */
public abstract class AbstractGimleMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  protected MavenProject project;

  @Override
  public final void execute() throws MojoExecutionException, MojoFailureException {
    if (!targetArtifactId().equals(project.getArtifactId())) {
      getLog().debug("skipping " + getClass().getSimpleName() + ": not " + targetArtifactId());
      return;
    }
    List<String> command = buildCommand();
    getLog().info("launching: " + String.join(" ", command));
    runAndWait(command);
  }

  /** The one reactor module this goal actually does anything in. */
  protected abstract String targetArtifactId();

  /** The full OS command line to spawn, built from this module's own resolved state. */
  protected abstract List<String> buildCommand() throws MojoExecutionException;

  private void runAndWait(List<String> command)
      throws MojoExecutionException, MojoFailureException {
    Process process;
    try {
      process = new ProcessBuilder(command).inheritIO().start();
    } catch (IOException e) {
      throw new MojoExecutionException("failed to start " + command.get(0), e);
    }
    // Backup for whatever signal-propagation the OS doesn't already handle on its own (a plain
    // Ctrl+C in a Windows console typically reaches both this process and the child directly) --
    // belt-and-suspenders, not the only path to a clean shutdown.
    Thread shutdownHook = new Thread(process::destroy, "gimle-mojo-shutdown-" + targetArtifactId());
    Runtime.getRuntime().addShutdownHook(shutdownHook);
    try {
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new MojoFailureException(targetArtifactId() + " exited with code " + exitCode);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroy();
    } finally {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException ignored) {
        // The JVM is already shutting down -- the hook either already ran or is redundant now.
      }
    }
  }

  /**
   * Resolves the real java launcher this Maven process itself is running under, mirroring {@code
   * gimle-agent}'s own {@code ResourceLimitEnforcementTest.javaExecutable()} -- so the spawned
   * child runs on the exact same JDK the developer already has active for this build.
   */
  protected static String javaExecutable() {
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
}
