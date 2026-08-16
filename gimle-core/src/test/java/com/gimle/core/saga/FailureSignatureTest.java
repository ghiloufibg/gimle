package com.gimle.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FailureSignatureTest {

  private static final String TYPE = "java.util.concurrent.TimeoutException";
  private static final String FRAME = "com.gimle.mimir.raft.RaftNode.propose(RaftNode.java)";

  @Test
  void run_specific_numbers_do_not_change_the_signature() {
    String a = FailureSignature.of(TYPE, "port 19091 refused after 5000 ms", FRAME);
    String b = FailureSignature.of(TYPE, "port 18080 refused after 12 ms", FRAME);
    assertEquals(a, b);
  }

  @Test
  void hex_identifiers_do_not_change_the_signature() {
    String a = FailureSignature.of(TYPE, "worker 6d0f79ab41c2 never registered", FRAME);
    String b = FailureSignature.of(TYPE, "worker deadbeef0042 never registered", FRAME);
    assertEquals(a, b);
  }

  @Test
  void different_exception_types_yield_different_signatures() {
    assertNotEquals(
        FailureSignature.of("java.lang.AssertionError", "boom", FRAME),
        FailureSignature.of(TYPE, "boom", FRAME));
  }

  @Test
  void genuinely_different_messages_yield_different_signatures() {
    assertNotEquals(
        FailureSignature.of(TYPE, "condition not met: deployment active", FRAME),
        FailureSignature.of(TYPE, "condition not met: node registered", FRAME));
  }

  @Test
  void null_message_and_frame_still_yield_a_stable_signature() {
    assertEquals(FailureSignature.of(TYPE, null, null), FailureSignature.of(TYPE, null, null));
  }

  @Test
  void signature_is_a_fixed_length_hex_string() {
    String signature = FailureSignature.of(TYPE, "anything at all", FRAME);
    assertEquals(16, signature.length());
    assertTrue(signature.matches("[0-9a-f]{16}"));
  }

  @Test
  void oversized_messages_are_truncated_before_hashing_so_the_tail_is_insignificant() {
    String base = "x".repeat(300);
    assertEquals(
        FailureSignature.of(TYPE, base + "tail one", FRAME),
        FailureSignature.of(TYPE, base + "completely different tail", FRAME));
  }
}
