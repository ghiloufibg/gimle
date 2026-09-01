package com.gimle.mimir.authz;

import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Verb;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.AuditOutcome;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.pki.CertificateRotationListener;
import com.gimle.pki.CertificateRotationStatus;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a process's own certificate rotations into the same durable audit trail every
 * authorization decision lands in, so "when did this node last renew, and when did renewal start
 * failing" survives the log rotation -- and the process -- that a plain log line does not.
 *
 * <p>Only two points in a failure streak are recorded, not every check: a rotation check runs every
 * few seconds, so auditing each failure would append thousands of identical entries for one outage.
 * The first failure marks when renewal stopped working, and the {@link #ESCALATION_AUDIT_AT}th
 * marks it as no longer a blip; the live gauge {@code CertificateRotationMetrics} publishes is what
 * carries the state in between.
 */
public final class CertificateRotationAuditor implements CertificateRotationListener {

  private static final Logger auditLog = LoggerFactory.getLogger("com.gimle.pki.audit");
  private static final Logger log = LoggerFactory.getLogger(CertificateRotationAuditor.class);

  private static final int ESCALATION_AUDIT_AT = 3;

  private final MutationSink mutations;
  private final String principal;

  /**
   * @param principal the identity of the process whose certificate this is -- its node id or
   *     process id, since a rotation is an action a process takes on its own behalf rather than on
   *     behalf of a caller
   */
  public CertificateRotationAuditor(MutationSink mutations, String principal) {
    if (mutations == null) {
      throw new IllegalArgumentException("mutations must not be null");
    }
    if (principal == null || principal.isBlank()) {
      throw new IllegalArgumentException("principal must not be blank");
    }
    this.mutations = mutations;
    this.principal = principal;
  }

  @Override
  public void onCheck(CertificateRotationStatus status) {
    if (!shouldAudit(status)) {
      return;
    }
    auditLog.info(
        "principal={} target=own-certificate verb={} outcome={} consecutiveFailures={} detail={}",
        principal,
        Verb.WRITE,
        status.outcome(),
        status.consecutiveFailures(),
        status.failureMessage().orElseGet(() -> notAfterDetail(status)));
    try {
      mutations.propose(
          new StateMutation.AppendAuditEvent(
              new AuditEvent(
                  UUID.randomUUID().toString(),
                  principal,
                  Set.of(),
                  ResourceKind.CERTIFICATE_REQUEST.name(),
                  Verb.WRITE.name(),
                  Optional.empty(),
                  Optional.of("own-certificate"),
                  true,
                  status.failed() ? AuditOutcome.REJECTED : AuditOutcome.APPLIED,
                  System.currentTimeMillis())));
    } catch (RuntimeException e) {
      // An unreachable store must never stop a process renewing its own certificate -- the whole
      // point of auditing the failure is that the rotation loop keeps running.
      log.warn("failed to record certificate rotation audit event: {}", e.getMessage());
    }
  }

  private static boolean shouldAudit(CertificateRotationStatus status) {
    if (status.rotated()) {
      return true;
    }
    return status.failed()
        && (status.consecutiveFailures() == 1
            || status.consecutiveFailures() == ESCALATION_AUDIT_AT);
  }

  private static String notAfterDetail(CertificateRotationStatus status) {
    return "validUntil=" + status.currentNotAfter().map(Object::toString).orElse("unknown");
  }
}
