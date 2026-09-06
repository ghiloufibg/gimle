package com.gimle.mavenplugin;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
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
 *
 * <p>With no explicit {@code gimle.saga.runId}, the run id is derived from the swept reports
 * themselves ({@link #deriveRunId(List, Path)}) rather than from the wall clock: importing an
 * unchanged set of reports repeatedly folds into the same run instead of minting a new one every
 * time. Nothing outside the report bytes enters the digest, so the import's own timing is
 * irrelevant while the per-test durations and outcomes recorded *inside* each report still separate
 * two genuinely different test runs.
 */
@Mojo(name = "saga-import", threadSafe = true)
public final class SagaImportMojo extends AbstractGimleRootMojo {

  // Package-private, not private: SagaImportMojoTest sets these directly rather than through
  // Maven's own plexus injection, the same seam AbstractGimleRootMojo's own project/session
  // fields already provide for testing executeAtRoot() without a live Maven session.
  @Parameter(property = "gimle.saga.port", defaultValue = "9096")
  String port;

  /** Run to import into; unset derives one from the swept reports' own content. */
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
    String effectiveRunId = runId == null || runId.isBlank() ? deriveRunId(reports, root) : runId;
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

  /**
   * A run id that is a pure function of what is being imported: SHA-256 over every swept report's
   * reactor-relative path and its exact bytes, in the sweep's own already-stable sorted order. The
   * path is part of the digest so the same suite results appearing under a different module stay a
   * different import; the file's modification time deliberately is not, since re-running this goal
   * rewrites nothing and an unchanged report set must keep folding into the same run. Nothing about
   * the machine or the moment of the import contributes either -- not the wall clock, not the
   * checkout's location, not the commit currently checked out -- since none of those changes what
   * is being imported, and letting any of them in is exactly what turns a repeated import of one
   * test run into several runs. Two genuinely different test runs still separate: a surefire report
   * records each suite's and each test case's own measured duration and outcome, so their bytes
   * differ even when every test passed both times.
   */
  static String deriveRunId(List<Path> reports, Path root) {
    MessageDigest digest = sha256();
    for (Path report : reports) {
      digest.update(relativize(root, report).getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      try {
        digest.update(Files.readAllBytes(report));
      } catch (IOException e) {
        throw new UncheckedIOException("failed to read surefire report " + report, e);
      }
      digest.update((byte) 0);
    }
    return "import-" + HexFormat.of().formatHex(digest.digest()).substring(0, 16);
  }

  private static String relativize(Path root, Path report) {
    Path relative = report.startsWith(root) ? root.relativize(report) : report;
    // '/' unconditionally, so the same checkout imported on Windows and on Linux digests alike.
    return relative.toString().replace(File.separatorChar, '/');
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every JRE", e);
    }
  }
}
