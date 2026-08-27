package com.gimle.ragnarok.surtr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.ragnarok.surtr.Measurements.FailureCounts;
import com.gimle.ragnarok.surtr.Measurements.StartupLatency;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SurtrReport#renderSummary} must stay in lockstep with {@link SurtrReport#render}: {@code
 * ragnarok report --surtr-report} has no live {@link SurtrRunResult}, only the {@code summary.json}
 * {@link SurtrReport#write} already produced, so this asserts the two really do print the same
 * content for the same run rather than letting the offline path silently drop fields again.
 */
final class SurtrReportTest {

  private static SurtrRunResult sampleResult() {
    return new SurtrRunResult(
        "pause-density",
        1.5,
        "n/a (targets an already-live cluster via --target)",
        1_000L,
        16_000L,
        List.of(new SurtrRunResult.JobSummary("fill", "create", 10, 15_000L, 10, 0)),
        Optional.of(
            new StartupLatency(
                Map.of(
                    "startingToActive", Percentiles.of(List.of(3L, 15L)),
                    "submitToActive", Percentiles.of(List.of(9563L, 9852L))))),
        List.of(),
        Optional.empty(),
        new FailureCounts(0, 0, 0),
        List.of(),
        List.of(new SurtrRunResult.GateOutcome("maxFailedSubmissions", 0, 0, true)));
  }

  private static Map<String, Object> writtenSummary(final Path runsRoot) throws IOException {
    final Path runDir = SurtrReport.write(sampleResult(), runsRoot);
    return Json.asObject(Json.parse(Files.readString(runDir.resolve("summary.json"))));
  }

  @Test
  void render_summary_of_a_written_report_matches_the_live_render(@TempDir final Path tempDir)
      throws IOException {
    final String live = SurtrReport.render(sampleResult());
    final String offline = SurtrReport.renderSummary(writtenSummary(tempDir));

    assertEquals(live, offline);
  }

  @Test
  void render_summary_includes_the_scale_factor_and_startup_percentiles(@TempDir final Path tempDir)
      throws IOException {
    final String rendered = SurtrReport.renderSummary(writtenSummary(tempDir));

    assertTrue(rendered.contains("(scale 1.5)"), rendered);
    assertTrue(rendered.contains("startup startingToActive"), rendered);
    assertTrue(rendered.contains("startup submitToActive"), rendered);
    assertTrue(rendered.contains("gate maxFailedSubmissions"), rendered);
  }
}
