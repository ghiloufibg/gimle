package com.gimle.mavenplugin;

import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * {@code mvn gimle:docs} -- runs the developer documentation site's full build pipeline in one
 * command: aggregate Javadoc across the platform modules, then build {@code gimle-docs} itself
 * (which copies that Javadoc into its own {@code static/javadoc/} and runs the Docusaurus build) --
 * the same two steps {@code gimle-docs/pom.xml}'s own description documents running by hand as
 * {@code mvn javadoc:aggregate} followed by {@code mvn -P docs -pl gimle-docs install}. Unlike
 * {@link ControlPlaneMojo}/{@link AgentMojo}/{@link DeployMojo}, this doesn't extend {@link
 * AbstractGimleMojo}: its job is a fixed 2-step pipeline (spawn one child {@code mvn} process, then
 * another), not "spawn one subprocess and wait." A direct goal invocation with no bound {@code
 * <phase>} still iterates the whole reactor by default (see {@link AbstractGimleMojo}'s own
 * javadoc), so this self-filters to the root aggregator project itself (artifactId {@code "gimle"})
 * rather than one leaf module -- the root project is always present in any reactor build regardless
 * of {@code -pl}/profile flags, which guarantees exactly one execution.
 *
 * <p>Both steps run as genuinely separate child Maven processes rather than folding into this
 * build's own in-flight reactor: {@code gimle-docs} only joins a reactor when the {@code docs}
 * profile is active (see the root pom), and {@code javadoc:aggregate} is deliberately never bound
 * to a lifecycle phase (see the root pom's {@code maven-javadoc-plugin} {@code pluginManagement}
 * entry), so nothing else chains either of them automatically -- this goal exists precisely to run
 * both, in the right order, in one command.
 */
@Mojo(name = "docs", threadSafe = true)
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
    String mavenExecutable = GimleProcesses.mavenExecutable();

    List<String> javadocCommand = javadocAggregateCommand(mavenExecutable);
    getLog().info("running: " + String.join(" ", javadocCommand));
    runAndWait(root, javadocCommand, "docs-javadoc-aggregate");

    List<String> docsBuildCommand = docsBuildCommand(mavenExecutable);
    getLog().info("running: " + String.join(" ", docsBuildCommand));
    runAndWait(root, docsBuildCommand, "docs-site-build");
  }

  /**
   * Pure command construction, split out from {@link #execute()} so it's unit-testable without
   * Maven's own parameter-injection machinery. No bound phase: {@code aggregate} needs the full
   * reactor project list, which only a direct invocation against the root aggregator gets.
   */
  static List<String> javadocAggregateCommand(String mavenExecutable) {
    return List.of(mavenExecutable, "javadoc:aggregate");
  }

  /**
   * Activates the {@code docs} profile (the only way {@code gimle-docs} joins a reactor, see the
   * root pom) and targets {@code gimle-docs} directly, exactly matching the manually-documented
   * working invocation ({@code gimle-docs/pom.xml}'s own description) rather than reimplementing
   * that module's Bun/copy steps here.
   */
  static List<String> docsBuildCommand(String mavenExecutable) {
    return List.of(mavenExecutable, "-P", "docs", "-pl", "gimle-docs", "install");
  }

  private static void runAndWait(Path workingDirectory, List<String> command, String label)
      throws MojoExecutionException, MojoFailureException {
    int exitCode = GimleProcesses.startAndAwaitExit(command, workingDirectory, label);
    if (exitCode != 0) {
      throw new MojoFailureException(String.join(" ", command) + " exited with code " + exitCode);
    }
  }
}
