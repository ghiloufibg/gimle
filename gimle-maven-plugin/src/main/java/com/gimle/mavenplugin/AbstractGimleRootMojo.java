package com.gimle.mavenplugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Shared base for {@code gimle:*} goals that orchestrate at the reactor level rather than acting
 * inside one specific module. A direct goal invocation with no bound {@code <phase>} iterates the
 * whole reactor (see {@link AbstractGimleMojo}); where that base self-filters to exactly one named
 * module, this one guards on {@link MavenProject#isExecutionRoot()} -- true for precisely one
 * project in any reactor invocation, regardless of {@code -T} or module count -- because an
 * orchestrator may need to see or touch several modules, not exactly one.
 */
public abstract class AbstractGimleRootMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  protected MavenProject project;

  @Override
  public final void execute() throws MojoExecutionException, MojoFailureException {
    if (!project.isExecutionRoot()) {
      getLog().debug("skipping " + getClass().getSimpleName() + ": not the execution root");
      return;
    }
    executeAtRoot();
  }

  /** Runs exactly once per reactor invocation, in the execution-root project. */
  protected abstract void executeAtRoot() throws MojoExecutionException, MojoFailureException;
}
