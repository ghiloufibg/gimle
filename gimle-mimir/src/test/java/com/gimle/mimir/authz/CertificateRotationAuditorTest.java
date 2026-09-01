package com.gimle.mimir.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.ResourceKind;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.AuditOutcome;
import com.gimle.mimir.raft.MutationOutcome;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.pki.CertificateRotationOutcome;
import com.gimle.pki.CertificateRotationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CertificateRotationAuditorTest {

  private static final Instant EXPIRY = Instant.parse("2026-06-01T00:00:00Z");

  @Test
  void a_completed_rotation_is_recorded_as_an_applied_certificate_write() {
    List<StateMutation> proposed = new ArrayList<>();
    CertificateRotationAuditor auditor = auditor(proposed);

    auditor.onCheck(status(CertificateRotationOutcome.ROTATED, 0, Optional.empty()));

    assertEquals(1, proposed.size());
    AuditEvent event = ((StateMutation.AppendAuditEvent) proposed.getFirst()).event();
    assertEquals("node-a", event.principal());
    assertEquals(ResourceKind.CERTIFICATE_REQUEST.name(), event.resourceKind());
    assertEquals(Optional.of("own-certificate"), event.targetId());
    assertEquals(AuditOutcome.APPLIED, event.outcome());
  }

  @Test
  void the_first_failure_of_a_streak_is_recorded_as_a_rejected_write() {
    List<StateMutation> proposed = new ArrayList<>();
    CertificateRotationAuditor auditor = auditor(proposed);

    auditor.onCheck(status(CertificateRotationOutcome.FAILED, 1, Optional.of("refused")));

    AuditEvent event = ((StateMutation.AppendAuditEvent) proposed.getFirst()).event();
    assertEquals(AuditOutcome.REJECTED, event.outcome());
  }

  @Test
  void an_ongoing_failure_streak_is_recorded_only_at_its_start_and_its_escalation_point() {
    List<StateMutation> proposed = new ArrayList<>();
    CertificateRotationAuditor auditor = auditor(proposed);

    for (int failures = 1; failures <= 10; failures++) {
      auditor.onCheck(status(CertificateRotationOutcome.FAILED, failures, Optional.of("refused")));
    }

    // Checks run every few seconds; one entry per failed check would bury the trail in noise.
    assertEquals(2, proposed.size());
  }

  @Test
  void an_ordinary_not_due_check_is_never_audited() {
    List<StateMutation> proposed = new ArrayList<>();
    CertificateRotationAuditor auditor = auditor(proposed);

    auditor.onCheck(status(CertificateRotationOutcome.NOT_DUE, 0, Optional.empty()));
    auditor.onCheck(status(CertificateRotationOutcome.DISABLED, 0, Optional.empty()));

    assertTrue(proposed.isEmpty());
  }

  @Test
  void an_unreachable_store_never_propagates_out_of_the_rotation_check() {
    CertificateRotationAuditor auditor =
        new CertificateRotationAuditor(
            mutation -> {
              throw new IllegalStateException("no reachable store leader");
            },
            "node-a");

    auditor.onCheck(status(CertificateRotationOutcome.FAILED, 1, Optional.of("refused")));

    assertFalse(Thread.currentThread().isInterrupted());
  }

  private static CertificateRotationAuditor auditor(List<StateMutation> proposed) {
    return new CertificateRotationAuditor(
        mutation -> {
          proposed.add(mutation);
          return MutationOutcome.accepted();
        },
        "node-a");
  }

  private static CertificateRotationStatus status(
      CertificateRotationOutcome outcome, int consecutiveFailures, Optional<String> message) {
    return new CertificateRotationStatus(
        outcome,
        Optional.of(EXPIRY),
        consecutiveFailures,
        message,
        Optional.of(EXPIRY.minus(Duration.ofDays(1))));
  }
}
