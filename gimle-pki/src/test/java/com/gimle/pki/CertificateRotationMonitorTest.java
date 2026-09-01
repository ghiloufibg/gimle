package com.gimle.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.Test;

class CertificateRotationMonitorTest {

  private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

  @Test
  void a_successful_check_reports_the_certificates_own_remaining_validity() throws Exception {
    X509Certificate leaf = signLeaf(Duration.ofDays(30));
    CertificateRotationMonitor monitor = monitor(CertificateRotationListener.NONE);

    CertificateRotationStatus status = monitor.notDue(leaf);

    assertEquals(CertificateRotationOutcome.NOT_DUE, status.outcome());
    assertEquals(0, status.consecutiveFailures());
    assertEquals(Optional.of(leaf.getNotAfter().toInstant()), status.currentNotAfter());
    assertTrue(status.remainingValidity(NOW).orElseThrow().toDays() > 0);
    assertEquals(Optional.of(NOW.plusSeconds(5)), status.nextCheckAt());
  }

  @Test
  void consecutive_failures_accumulate_and_carry_the_still_valid_certificates_expiry()
      throws Exception {
    X509Certificate leaf = signLeaf(Duration.ofDays(30));
    CertificateRotationMonitor monitor = monitor(CertificateRotationListener.NONE);

    assertEquals(1, monitor.failed("connection refused", leaf).consecutiveFailures());
    assertEquals(2, monitor.failed("connection refused", leaf).consecutiveFailures());
    CertificateRotationStatus third = monitor.failed("connection refused", leaf);

    assertEquals(3, third.consecutiveFailures());
    assertEquals(3, monitor.consecutiveFailures());
    assertTrue(third.failed());
    assertEquals(Optional.of("connection refused"), third.failureMessage());
    // The whole point of tracking this alongside the failure: the certificate is still good, so
    // the operator can see exactly how much runway a repeatedly-failing renewal still has.
    assertEquals(Optional.of(leaf.getNotAfter().toInstant()), third.currentNotAfter());
  }

  @Test
  void a_successful_check_resets_the_failure_streak() throws Exception {
    X509Certificate leaf = signLeaf(Duration.ofDays(30));
    CertificateRotationMonitor monitor = monitor(CertificateRotationListener.NONE);
    monitor.failed("connection refused", leaf);
    monitor.failed("connection refused", leaf);

    assertEquals(0, monitor.rotated(leaf).consecutiveFailures());
    assertEquals(0, monitor.consecutiveFailures());
  }

  @Test
  void a_failure_with_an_unreadable_certificate_reports_no_expiry_at_all() {
    CertificateRotationMonitor monitor = monitor(CertificateRotationListener.NONE);

    CertificateRotationStatus status = monitor.failed("certificate file is corrupt", null);

    assertTrue(status.failed());
    assertEquals(Optional.empty(), status.currentNotAfter());
    assertEquals(Optional.empty(), status.remainingValidity(NOW));
  }

  @Test
  void plaintext_transport_reports_disabled_and_clears_any_earlier_streak() throws Exception {
    X509Certificate leaf = signLeaf(Duration.ofDays(30));
    CertificateRotationMonitor monitor = monitor(CertificateRotationListener.NONE);
    monitor.failed("connection refused", leaf);

    CertificateRotationStatus status = monitor.disabled();

    assertEquals(CertificateRotationOutcome.DISABLED, status.outcome());
    assertEquals(0, status.consecutiveFailures());
    assertFalse(status.rotated());
  }

  @Test
  void every_check_is_reported_to_the_listener_whatever_its_outcome() throws Exception {
    X509Certificate leaf = signLeaf(Duration.ofDays(30));
    List<CertificateRotationStatus> seen = new ArrayList<>();
    CertificateRotationMonitor monitor = monitor(seen::add);

    monitor.notDue(leaf);
    monitor.failed("connection refused", leaf);
    monitor.rotated(leaf);

    assertEquals(
        List.of(
            CertificateRotationOutcome.NOT_DUE,
            CertificateRotationOutcome.FAILED,
            CertificateRotationOutcome.ROTATED),
        seen.stream().map(CertificateRotationStatus::outcome).toList());
  }

  @Test
  void a_listener_that_throws_never_breaks_the_rotation_check_that_produced_it() throws Exception {
    X509Certificate leaf = signLeaf(Duration.ofDays(30));
    CertificateRotationMonitor monitor =
        monitor(
            status -> {
              throw new IllegalStateException("store unreachable");
            });

    assertEquals(1, monitor.failed("connection refused", leaf).consecutiveFailures());
    assertEquals(0, monitor.notDue(leaf).consecutiveFailures());
  }

  @Test
  void chained_listeners_both_run_even_when_the_first_one_throws() throws Exception {
    X509Certificate leaf = signLeaf(Duration.ofDays(30));
    List<CertificateRotationStatus> seen = new ArrayList<>();
    CertificateRotationListener throwing =
        status -> {
          throw new IllegalStateException("store unreachable");
        };
    CertificateRotationMonitor monitor = monitor(throwing.andThen(seen::add));

    monitor.notDue(leaf);

    assertEquals(1, seen.size());
  }

  private static CertificateRotationMonitor monitor(CertificateRotationListener listener) {
    return new CertificateRotationMonitor(
        "test", Duration.ofSeconds(5), listener, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static X509Certificate signLeaf(Duration validity) throws NoSuchAlgorithmException {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=ca"), Duration.ofDays(3650));
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair leafKeyPair = generator.generateKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(leafKeyPair, new X500Name("CN=test-leaf"));
    return ca.signCertificateRequest(csr, validity);
  }
}
