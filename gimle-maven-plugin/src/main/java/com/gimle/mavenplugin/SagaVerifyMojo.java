package com.gimle.mavenplugin;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * {@code mvn gimle:verify} -- runs a full build under Saga run tracking: ensures a Saga server is
 * up (exactly like {@link SagaMojo}), mints a run id from the wall clock and the working tree's
 * short git sha, announces the run over {@code /api/ingest}, then spawns the actual build -- {@code
 * mvn <gimle.saga.mavenArgs>} plus {@code -Dgimle.saga.endpoint}/{@code -Dgimle.saga.runId} so
 * everything inside can report into the same run -- as a genuinely separate child Maven process
 * from the execution root, streaming its output through. Never in-reactor: this goal's own reactor
 * is already mid-flight when it runs, and a nested build sharing it would contend for the same
 * modules and thread pool.
 *
 * <p>After the child exits (pass or fail), every {@code target/surefire-reports/*.xml} written
 * since the run started is swept and posted to {@code /api/import} as a safety net for anything the
 * build didn't stream itself, a run-finished event with the swept totals closes the run, the run's
 * console deep link is printed, and only then does a non-zero child exit fail this build too.
 */
@Mojo(name = "verify", threadSafe = true)
public final class SagaVerifyMojo extends AbstractGimleRootMojo {

  private static final DateTimeFormatter RUN_ID_STAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

  @Parameter(property = "gimle.saga.port", defaultValue = "9096")
  private String port;

  /** Whitespace-separated arguments for the child build, e.g. {@code "clean verify -Psmoke"}. */
  @Parameter(property = "gimle.saga.mavenArgs", defaultValue = "verify")
  private String mavenArgs;

  @Parameter(property = "gimle.saga.serverVersion", defaultValue = "${plugin.version}")
  private String serverVersion;

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
    SagaClient client = new SagaClient("http://127.0.0.1:" + port);
    SagaServer.ensureRunning(client, this::spawnServer, SagaMojo.STARTUP_TIMEOUT, getLog());

    Path root = project.getBasedir().toPath();
    // Truncated to seconds so a report written within the run's first second isn't excluded by a
    // filesystem's own second-granularity mtime rounding down past the exact start instant.
    Instant runStart = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    GitInfo git = GitInfo.capture(root);
    String runId = mintRunId(LocalDateTime.now(), git.shortSha());
    List<String> command =
        childMavenCommand(GimleProcesses.mavenExecutable(), mavenArgs, client.endpoint(), runId);
    client.ingest(
        SagaEvents.runStarted(
            runId, runStart, git.shortSha(), git.branch(), String.join(" ", command)));
    getLog().info("run " + runId + " -- launching: " + String.join(" ", command));
    int exitCode = GimleProcesses.startAndAwaitExit(command, root, "saga-verify-child");

    List<Path> reports = SurefireReports.sweep(root, runStart);
    int imported = 0;
    for (Path report : reports) {
      try {
        client.importReport(runId, report);
        imported++;
      } catch (MojoExecutionException e) {
        getLog().warn("failed to import " + report + ": " + e.getMessage());
      }
    }
    SurefireReports.Totals totals = SurefireReports.totals(reports, getLog());
    try {
      client.ingest(SagaEvents.runFinished(runId, Instant.now(), totals));
    } catch (MojoExecutionException e) {
      // The child build's own verdict must not be masked by a reporting hiccup this late.
      getLog().warn("failed to post the run-finished event: " + e.getMessage());
    }

    getLog()
        .info(
            "imported "
                + imported
                + "/"
                + reports.size()
                + " surefire report(s): "
                + totals.tests()
                + " tests, "
                + totals.failures()
                + " failures, "
                + totals.errors()
                + " errors, "
                + totals.skipped()
                + " skipped");
    getLog().info("Saga run report: " + client.endpoint() + "/console/runs/" + runId);
    if (exitCode != 0) {
      throw new MojoFailureException("child build exited with code " + exitCode);
    }
  }

  /**
   * {@code <yyyy-MM-dd'T'HH-mm-ss>_<short sha>} -- filesystem- and URL-safe (no colons), sortable
   * by time, pinned to the commit under test; {@code _local} stands in when there is no git.
   */
  static String mintRunId(LocalDateTime at, Optional<String> shortSha) {
    return at.format(RUN_ID_STAMP) + "_" + shortSha.orElse("local");
  }

  static List<String> childMavenCommand(
      String mavenExecutable, String mavenArgs, String endpoint, String runId) {
    List<String> command = new ArrayList<>();
    command.add(mavenExecutable);
    for (String arg : mavenArgs.trim().split("\\s+")) {
      if (!arg.isEmpty()) {
        command.add(arg);
      }
    }
    command.add("-Dgimle.saga.endpoint=" + endpoint);
    command.add("-Dgimle.saga.runId=" + runId);
    // A build-cache restore skips surefire entirely, so a cached child would record zero test
    // events -- executing the tests is the whole point of a report run. Restores are bypassed
    // (the cache is still written) rather than disabling the extension outright.
    command.add("-Dmaven.build.cache.skipCache=true");
    return List.copyOf(command);
  }

  private Process spawnServer() throws MojoExecutionException {
    String classpath =
        GimleProcesses.resolveRuntimeClasspath(
            "gimle-saga",
            serverVersion,
            remoteRepositories,
            repositorySystemSession,
            repositorySystem);
    return SagaServer.spawnDetached(
        SagaServer.spawnCommand(GimleProcesses.javaExecutable(), classpath, port), getLog());
  }
}
