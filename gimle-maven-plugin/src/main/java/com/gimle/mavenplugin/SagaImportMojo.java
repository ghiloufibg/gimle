package com.gimle.mavenplugin;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * {@code mvn gimle:saga-import} -- standalone sweep of every {@code target/surefire-reports/*.xml}
 * under the execution root (no timestamp filter -- whatever the last builds left behind), posted to
 * an already-running Saga server's {@code /api/import}. For pulling an ordinary {@code mvn verify}
 * run's results into Saga after the fact; {@link SagaVerifyMojo} does the same sweep automatically
 * around a child build. Unlike the other saga goals this never spawns a server -- an import with
 * nothing listening is an error, not a reason to boot one.
 */
@Mojo(name = "saga-import", threadSafe = true)
public final class SagaImportMojo extends AbstractGimleRootMojo {

  // Package-private, not private: SagaImportMojoTest sets these directly rather than through
  // Maven's own plexus injection, the same seam AbstractGimleRootMojo's own project/session
  // fields already provide for testing executeAtRoot() without a live Maven session.
  @Parameter(property = "gimle.saga.port", defaultValue = "9096")
  String port;

  /** Run to import into; unset mints a fresh one the same way {@link SagaVerifyMojo} does. */
  @Parameter(property = "gimle.saga.runId")
  String runId;

  @Override
  protected void executeAtRoot() throws MojoExecutionException {
    SagaClient client = new SagaClient("http://127.0.0.1:" + port);
    if (!client.isHealthy()) {
      throw new MojoExecutionException(
          "no Saga server responding at " + client.endpoint() + " -- run `mvn gimle:saga` first");
    }
    Path root = reactorRoot();
    List<Path> reports = SurefireReports.sweep(root, null);
    if (reports.isEmpty()) {
      getLog().warn("no surefire reports found under " + root + "; nothing to import");
      return;
    }
    String effectiveRunId =
        runId == null || runId.isBlank()
            ? SagaVerifyMojo.mintRunId(LocalDateTime.now(), GitInfo.capture(root).shortSha())
            : runId;
    int imported = 0;
    for (Path report : reports) {
      try {
        client.importReport(effectiveRunId, report);
        imported++;
      } catch (MojoExecutionException e) {
        getLog().warn("failed to import " + report + ": " + e.getMessage());
      }
    }
    if (imported == 0) {
      throw new MojoExecutionException(
          "every one of the " + reports.size() + " report imports failed; see warnings above");
    }
    SurefireReports.Totals totals = SurefireReports.totals(reports, getLog());
    // Each importReport() call above posts exactly one report file's own XML to /api/import,
    // which the server folds in as a safety net for a run that may already exist -- only the
    // very first such call actually opens the run and records its own (single-file) totals as
    // the run's persisted summary; every later call's own totals are dropped by that same
    // fold, since a safety-net fold must never let a partial re-import reset an already-live
    // run. A standalone import has no live run to protect, so this explicit run-finished event,
    // computed from every swept report the same way the log line below already is, is what
    // makes the persisted summary (and the "passed"/"failed" run status derived from it) match
    // the real, complete totals rather than whichever single file happened to land first.
    client.ingest(SagaEvents.runFinished(effectiveRunId, Instant.now(), totals));
    getLog()
        .info(
            "imported "
                + imported
                + "/"
                + reports.size()
                + " surefire report(s) into run "
                + effectiveRunId
                + ": "
                + totals.tests()
                + " tests, "
                + totals.failures()
                + " failures, "
                + totals.errors()
                + " errors, "
                + totals.skipped()
                + " skipped");
    getLog().info("Saga run report: " + client.endpoint() + "/console/runs/" + effectiveRunId);
  }
}
