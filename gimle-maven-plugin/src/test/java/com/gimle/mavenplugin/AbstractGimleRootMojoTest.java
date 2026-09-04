package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

/**
 * {@link AbstractGimleRootMojo#reactorRoot()} is the fix for a real bug (GIMLE saga-import/
 * flaky-tests silently scoping themselves to one module's own directory under a {@code -pl
 * <submodules>} invocation that excludes the aggregator): it must resolve to the directory Maven
 * was actually invoked from, not {@link MavenProject#getBasedir()} of whichever project {@link
 * AbstractGimleRootMojo#isOrchestrationProject()} happens to pick when the execution-root project
 * itself isn't in the reactor.
 */
class AbstractGimleRootMojoTest {

  @Test
  void reactor_root_is_the_execution_root_directory_even_when_the_orchestration_project_is_not_it()
      throws Exception {
    File executionRoot = new File("/repo");
    DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
    request.setBaseDirectory(executionRoot);

    // Simulates `mvn gimle:x -pl gimle-os,gimle-core,gimle-mimir` run from the aggregator
    // directory: the aggregator's own pom is excluded from the reactor (no project here has
    // isExecutionRoot() true), so gimle-os -- reactor-order first, not the execution root -- is
    // the project this goal actually executes in.
    MavenProject gimleOs = projectAt("gimle-os", "/repo/gimle-os");
    MavenProject gimleCore = projectAt("gimle-core", "/repo/gimle-core");
    MavenSession session = new MavenSession(null, request, null, List.of(gimleOs, gimleCore));

    RecordingRootMojo mojo = new RecordingRootMojo();
    mojo.project = gimleOs;
    mojo.session = session;

    mojo.execute();

    assertTrue(mojo.executed, "the orchestration project must still run the goal body");
    assertEquals(Path.of("/repo"), mojo.capturedRoot);
    assertEquals(gimleOs.getBasedir().toPath(), mojo.capturedProjectBasedir);
    assertTrue(
        !mojo.capturedRoot.equals(mojo.capturedProjectBasedir),
        "reactorRoot() must not just be the orchestration project's own basedir");
  }

  @Test
  void a_non_orchestration_project_never_reaches_the_goal_body() throws Exception {
    File executionRoot = new File("/repo");
    DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
    request.setBaseDirectory(executionRoot);

    MavenProject gimleOs = projectAt("gimle-os", "/repo/gimle-os");
    MavenProject gimleCore = projectAt("gimle-core", "/repo/gimle-core");
    MavenSession session = new MavenSession(null, request, null, List.of(gimleOs, gimleCore));

    RecordingRootMojo mojo = new RecordingRootMojo();
    mojo.project = gimleCore;
    mojo.session = session;

    mojo.execute();

    assertTrue(!mojo.executed, "only the reactor's first project is the orchestration project");
  }

  /**
   * {@code artifactId} both names the fake module's own directory and gives {@link
   * MavenProject#equals} (compared by GAV, not identity) something distinct to key on -- two
   * default-constructed projects with no artifactId are indistinguishably "equal" to it, which
   * would make {@link AbstractGimleRootMojo#isOrchestrationProject()}'s own {@code equals} check
   * meaningless here.
   */
  private static MavenProject projectAt(String artifactId, String basedir) {
    MavenProject project = new MavenProject();
    project.setArtifactId(artifactId);
    project.setFile(new File(basedir, "pom.xml"));
    return project;
  }

  private static final class RecordingRootMojo extends AbstractGimleRootMojo {
    boolean executed;
    Path capturedRoot;
    Path capturedProjectBasedir;

    @Override
    protected void executeAtRoot() throws MojoExecutionException, MojoFailureException {
      executed = true;
      capturedRoot = reactorRoot();
      capturedProjectBasedir = project.getBasedir().toPath();
    }
  }
}
