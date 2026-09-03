package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.authz.Principal;
import com.gimle.core.protocol.CsrPurpose;
import com.gimle.core.protocol.CsrResult;
import com.gimle.core.protocol.CsrSubmission;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.Pem;
import com.gimle.pki.Subjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link WorkerCertificates} against a real {@link CertificateAuthority} standing in for the
 * control plane's own signing path -- what gets submitted, what lands on disk, and when a renewal
 * actually happens -- without an HTTP hop in the way.
 */
class WorkerCertificatesTest {

  @TempDir Path tempDir;

  private CertificateAuthority ca;
  private final List<CsrSubmission> submissions = new ArrayList<>();
  private final AtomicReference<Instant> clock = new AtomicReference<>(Instant.now());
  private WorkerCertificates certificates;

  @BeforeEach
  void setUp() {
    ca = CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    certificates =
        new WorkerCertificates(this::signLikeTheControlPlane, tempDir, "10.0.0.5", clock::get);
  }

  @Test
  void issues_a_worker_certificate_once_and_reuses_it_on_a_later_spawn() throws Exception {
    WorkerCertificates.Material first =
        certificates.ensureIssued("node-1", "orders#0", Optional.of("acme"));
    WorkerCertificates.Material second =
        certificates.ensureIssued("node-1", "orders#0", Optional.of("acme"));

    assertEquals(first, second);
    assertEquals(1, submissions.size());
    CsrSubmission submission = submissions.get(0);
    assertEquals(CsrPurpose.WORKER_CLIENT, submission.purpose());
    assertEquals(Optional.of("acme"), submission.tenantId());
    assertEquals(Optional.empty(), submission.bootstrapToken());
    assertEquals(
        Optional.of("node-1:orders#0"),
        Subjects.commonNameOf(Pem.decodeCsr(submission.csrPem()).getSubject()));
    Principal presented = Subjects.principalFrom(readCertificate(first));
    assertEquals("node-1:orders#0", presented.name());
    assertEquals(
        Set.of(BuiltinRoles.GROUP_WORKERS, BuiltinRoles.tenantGroup("acme")), presented.groups());
    assertTrue(Files.isRegularFile(first.keyFile()));
  }

  @Test
  void an_untenanted_worker_requests_no_tenant_group() throws Exception {
    WorkerCertificates.Material material =
        certificates.ensureIssued("node-1", "platform#0", Optional.empty());

    assertEquals(Optional.empty(), submissions.get(0).tenantId());
    assertEquals(
        Set.of(BuiltinRoles.GROUP_WORKERS),
        Subjects.principalFrom(readCertificate(material)).groups());
  }

  @Test
  void a_renewal_pass_reissues_only_a_certificate_that_is_due() throws Exception {
    WorkerCertificates.Material material =
        certificates.ensureIssued("node-1", "orders#0", Optional.of("acme"));
    X509Certificate original = readCertificate(material);
    Map<String, Optional<String>> supervised = new LinkedHashMap<>();
    supervised.put("orders#0", Optional.of("acme"));
    supervised.put("never-spawned#0", Optional.of("acme"));

    assertEquals(Set.of(), certificates.renewDue("node-1", supervised));
    assertEquals(1, submissions.size());

    // Well past the one-day validity the stand-in CA signs with: due by any renewal schedule.
    clock.set(clock.get().plus(Duration.ofDays(30)));

    assertEquals(Set.of("orders#0"), certificates.renewDue("node-1", supervised));
    assertEquals(2, submissions.size());
    assertNotEquals(original.getSerialNumber(), readCertificate(material).getSerialNumber());
  }

  @Test
  void a_refused_issuance_fails_the_spawn_and_leaves_no_material_behind() {
    WorkerCertificates refusing =
        new WorkerCertificates(
            submission -> {
              throw new IOException("worker certificate request rejected with status 403");
            },
            tempDir,
            "10.0.0.5",
            clock::get);

    assertThrows(
        IOException.class, () -> refusing.ensureIssued("node-1", "orders#0", Optional.of("acme")));

    WorkerCertificates.Material material = refusing.materialFor("orders#0");
    assertFalse(Files.exists(material.certFile()));
    assertFalse(Files.exists(material.keyFile()));
  }

  @Test
  void material_for_each_worker_lives_in_its_own_directory() {
    WorkerCertificates.Material orders = certificates.materialFor("orders#0");
    WorkerCertificates.Material billing = certificates.materialFor("billing#0");

    assertNotEquals(orders.certFile().getParent(), billing.certFile().getParent());
    assertEquals(orders.certFile().getParent(), orders.keyFile().getParent());
    assertEquals("a_b#0", WorkerCertificates.fileSafe("a/b#0"));
  }

  /** Mirrors {@code ApiServer#handleWorkerClientRequest}'s stamping: workers group plus tenant. */
  private CsrResult signLikeTheControlPlane(CsrSubmission submission) {
    submissions.add(submission);
    PKCS10CertificationRequest csr = Pem.decodeCsr(submission.csrPem());
    List<String> organizations = new ArrayList<>(List.of(BuiltinRoles.GROUP_WORKERS));
    submission.tenantId().ifPresent(id -> organizations.add(BuiltinRoles.tenantGroup(id)));
    X509Certificate signed =
        ca.signCertificateRequest(
            csr, Subjects.withOrganizations(csr.getSubject(), organizations), Duration.ofDays(1));
    return CsrResult.approved(
        Pem.encodeCertificate(signed), Pem.encodeCertificate(ca.certificate()));
  }

  private static X509Certificate readCertificate(WorkerCertificates.Material material)
      throws IOException {
    return Pem.decodeCertificate(Files.readString(material.certFile(), StandardCharsets.US_ASCII));
  }
}
