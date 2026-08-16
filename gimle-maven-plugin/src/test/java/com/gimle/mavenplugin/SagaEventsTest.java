package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.gimle.core.saga.SagaEvent;
import com.gimle.core.saga.SagaEventCodec;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SagaEventsTest {

  @Test
  void run_started_decodes_under_the_shared_wire_contract() {
    String line =
        SagaEvents.runStarted(
            "2026-08-16T10-30-05_abc1234",
            Instant.parse("2026-08-16T10:30:05Z"),
            Optional.of("abc1234"),
            Optional.of("main"),
            "mvn verify");
    SagaEvent decoded = SagaEventCodec.decode(line);
    assertEquals(
        new SagaEvent.RunStarted(
            "2026-08-16T10-30-05_abc1234",
            Instant.parse("2026-08-16T10:30:05Z").toEpochMilli(),
            "main",
            "abc1234",
            "mvn verify",
            List.of()),
        decoded);
  }

  @Test
  void run_started_without_git_identity_still_decodes() {
    String line =
        SagaEvents.runStarted(
            "r1", Instant.parse("2026-08-16T10:30:05Z"), Optional.empty(), Optional.empty(), "mvn");
    assertInstanceOf(SagaEvent.RunStarted.class, SagaEventCodec.decode(line));
  }

  @Test
  void run_finished_totals_derive_passed_and_fold_errors_into_failed() {
    String line =
        SagaEvents.runFinished(
            "r1",
            Instant.parse("2026-08-16T10:45:00Z"),
            new SurefireReports.Totals(10, 2, 1, 3, 1));
    SagaEvent.RunFinished decoded = (SagaEvent.RunFinished) SagaEventCodec.decode(line);
    assertEquals(new SagaEvent.RunTotals(10, 4, 3, 3, 1), decoded.totals());
  }

  @Test
  void inconsistent_report_attributes_never_yield_a_negative_passed_count() {
    String line =
        SagaEvents.runFinished(
            "r1", Instant.parse("2026-08-16T10:45:00Z"), new SurefireReports.Totals(1, 2, 2, 0, 0));
    SagaEvent.RunFinished decoded = (SagaEvent.RunFinished) SagaEventCodec.decode(line);
    assertEquals(0, decoded.totals().passed());
  }
}
