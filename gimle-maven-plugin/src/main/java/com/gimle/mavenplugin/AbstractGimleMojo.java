package com.gimle.mavenplugin;

import java.util.List;
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
    GimleProcesses.runAndWaitWithShutdownHook(command, targetArtifactId());
  }

  /** The one reactor module this goal actually does anything in. */
  protected abstract String targetArtifactId();

  /** The full OS command line to spawn, built from this module's own resolved state. */
  protected abstract List<String> buildCommand() throws MojoExecutionException;

  /**
   * @see GimleProcesses#javaExecutable()
   */
  protected static String javaExecutable() {
    return GimleProcesses.javaExecutable();
  }
}
