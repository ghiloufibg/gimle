package com.gimle.mavenplugin;

import java.nio.file.Path;
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

  @Parameter(property = "gimle.saga.port", defaultValue = "9096")
  private String port;

  /** Run to import into; unset mints a fresh one the same way {@link SagaVerifyMojo} does. */
  @Parameter(property = "gimle.saga.runId")
  private String runId;

  @Override
  protected void executeAtRoot() throws MojoExecutionException {
    SagaClient client = new SagaClient("http://127.0.0.1:" + port);
    if (!client.isHealthy()) {
      throw new MojoExecutionException(
          "no Saga server responding at " + client.endpoint() + " -- run `mvn gimle:saga` first");
    }
    Path root = project.getBasedir().toPath();
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
