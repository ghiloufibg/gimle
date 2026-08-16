package com.gimle.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleCodecException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SagaEventCodecTest {

  private static final String RUN_ID = "2026-08-16T09-14-03_a41f9c";
  private static final String TEST_ID =
      "gimle-mimir:RaftClusterTest#leader_election_and_write_replication_work";

  static Stream<SagaEvent> allEventVariants() {
    return Stream.of(
        new SagaEvent.RunStarted(
            RUN_ID,
            1_765_872_843_000L,
            "master",
            "a41f9c0",
            "mvn verify",
            List.of("gimle-core", "gimle-mimir")),
        new SagaEvent.TestStarted(RUN_ID, TEST_ID, List.of("smoke"), 1),
        new SagaEvent.TestStarted(RUN_ID, TEST_ID, List.of(), 2),
        new SagaEvent.TestFinished(
            RUN_ID,
            TEST_ID,
            1,
            SagaEvent.Outcome.FAILED,
            4_812L,
            Optional.of("java.util.concurrent.TimeoutException"),
            Optional.of("proposal not committed within PT5S"),
            Optional.of(FailureSignature.of("java.util.concurrent.TimeoutException", "x", null))),
        new SagaEvent.TestFinished(
            RUN_ID,
            TEST_ID,
            2,
            SagaEvent.Outcome.PASSED,
            1_204L,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()),
        new SagaEvent.Attachment(RUN_ID, "fenrir-ledger", "{\"strikes\":[]}"),
        new SagaEvent.RunFinished(
            RUN_ID, 1_765_873_001_000L, new SagaEvent.RunTotals(412, 409, 1, 1, 1)));
  }

  @ParameterizedTest
  @MethodSource("allEventVariants")
  void roundTripsEveryEventVariant(SagaEvent event) {
    assertEquals(event, SagaEventCodec.decode(SagaEventCodec.encode(event)));
  }

  @Test
  void encoded_event_is_a_single_line_naming_its_type_first() {
    String line =
        SagaEventCodec.encode(new SagaEvent.Attachment(RUN_ID, "topology", "{\"nodes\":1}"));
    assertFalse(line.contains("\n"));
    assertTrue(line.startsWith("{\"type\":\"attachment\""));
  }

  @Test
  void absent_failure_fields_are_omitted_from_the_encoded_line_not_written_as_null() {
    String line =
        SagaEventCodec.encode(
            new SagaEvent.TestFinished(
                RUN_ID,
                TEST_ID,
                1,
                SagaEvent.Outcome.PASSED,
                10L,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
    assertFalse(line.contains("failureType"));
    assertFalse(line.contains("null"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json at all",
        "[1,2,3]",
        "{\"type\":\"no-such-event\",\"runId\":\"r\"}",
        "{\"runId\":\"r\"}",
        "{\"type\":\"test-started\",\"runId\":\"r\",\"tags\":[],\"attempt\":1}",
        "{\"type\":\"test-started\",\"runId\":\"r\",\"testId\":\"t\",\"tags\":[1],\"attempt\":1}",
        "{\"type\":\"test-finished\",\"runId\":\"r\",\"testId\":\"t\",\"attempt\":1,"
            + "\"outcome\":\"EXPLODED\",\"durationMillis\":1}",
        "{\"type\":\"run-finished\",\"runId\":\"r\",\"finishedAtEpochMilli\":1,\"totals\":3}"
      })
  void rejectsMalformedLines(String line) {
    assertThrows(GimleCodecException.class, () -> SagaEventCodec.decode(line));
  }
}
