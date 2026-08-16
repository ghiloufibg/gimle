package com.gimle.mavenplugin;

import com.gimle.core.saga.SagaEvent;
import com.gimle.core.saga.SagaEventCodec;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Builds the NDJSON event lines a Saga server ingests, delegating to {@code gimle-core}'s own
 * {@link SagaEventCodec} so the plugin can never drift from the wire contract the server and the
 * test listener share.
 */
final class SagaEvents {

  private SagaEvents() {}

  static String runStarted(
      String runId,
      Instant at,
      Optional<String> gitSha,
      Optional<String> gitBranch,
      String command) {
    return SagaEventCodec.encode(
        new SagaEvent.RunStarted(
            runId, at.toEpochMilli(), gitBranch.orElse(""), gitSha.orElse(""), command, List.of()));
  }

  /**
   * The orchestrator's summary of the swept reports. {@code passed} is derived (tests minus
   * failed/skipped, floored at zero for defensive robustness against inconsistent report
   * attributes); failures and errors both count as failed.
   */
  static String runFinished(String runId, Instant at, SurefireReports.Totals totals) {
    int failed = totals.failures() + totals.errors();
    int passed = Math.max(0, totals.tests() - failed - totals.skipped());
    return SagaEventCodec.encode(
        new SagaEvent.RunFinished(
            runId,
            at.toEpochMilli(),
            new SagaEvent.RunTotals(
                totals.tests(), passed, failed, totals.skipped(), totals.flaky())));
  }
}
