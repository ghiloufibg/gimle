package com.gimle.mavenplugin;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Shared base for {@code gimle:*} goals that orchestrate at the reactor level rather than acting
 * inside one specific module. A direct goal invocation with no bound {@code <phase>} iterates the
 * whole reactor (see {@link AbstractGimleMojo}); where that base self-filters to exactly one named
 * module, this one runs exactly once per reactor invocation -- in the execution-root project when
 * the reactor contains one, or in the reactor's first project when it doesn't (a {@code -pl
 * <submodule>} invocation from an aggregator directory: the aggregator itself is the execution root
 * but is excluded from the reactor, so guarding on {@link MavenProject#isExecutionRoot()} alone
 * would silently run the goal zero times and still report {@code BUILD SUCCESS}).
 */
public abstract class AbstractGimleRootMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  protected MavenProject project;

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  protected MavenSession session;

  @Override
  public final void execute() throws MojoExecutionException, MojoFailureException {
    if (!isOrchestrationProject()) {
      getLog().debug("skipping " + getClass().getSimpleName() + ": not the orchestration project");
      return;
    }
    executeAtRoot();
  }

  private boolean isOrchestrationProject() {
    if (project.isExecutionRoot()) {
      return true;
    }
    boolean reactorHasExecutionRoot =
        session.getProjects().stream().anyMatch(MavenProject::isExecutionRoot);
    return !reactorHasExecutionRoot && project.equals(session.getProjects().get(0));
  }

  /** Runs exactly once per reactor invocation, in the orchestration project chosen above. */
  protected abstract void executeAtRoot() throws MojoExecutionException, MojoFailureException;
}
