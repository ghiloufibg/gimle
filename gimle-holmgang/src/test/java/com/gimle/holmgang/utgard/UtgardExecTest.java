package com.gimle.holmgang.utgard;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.HolmgangException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link UtgardExec}'s assertion logic against hand-built {@link UtgardExecResult} values
 * -- no container, no Docker daemon, so this runs under the module's default profile.
 */
class UtgardExecTest {

  @Test
  void require_success_returns_the_same_result_on_a_zero_exit_code() {
    final UtgardExecResult result =
        new UtgardExecResult(List.of("hilmir", "validate"), 0, "ok", "");
    assertSame(result, UtgardExec.requireSuccess(result, "validate"));
  }

  @Test
  void require_success_throws_with_the_full_transcript_on_a_nonzero_exit_code() {
    final UtgardExecResult result =
        new UtgardExecResult(
            List.of("hilmir", "up", "--machine", "helm-1"), 1, "starting...", "boom: no fafnir");
    final HolmgangException error =
        assertThrows(
            HolmgangException.class, () -> UtgardExec.requireSuccess(result, "machine up"));
    assertTrue(error.getMessage().contains("machine up failed"));
    assertTrue(error.getMessage().contains("exit 1"));
    assertTrue(error.getMessage().contains("hilmir up --machine helm-1"));
    assertTrue(error.getMessage().contains("starting..."));
    assertTrue(error.getMessage().contains("boom: no fafnir"));
  }

  @Test
  void a_blank_captured_stream_is_still_valid() {
    final UtgardExecResult result = new UtgardExecResult(List.of("hilmir", "status"), 0, "", "");
    assertTrue(result.succeeded());
  }
}
