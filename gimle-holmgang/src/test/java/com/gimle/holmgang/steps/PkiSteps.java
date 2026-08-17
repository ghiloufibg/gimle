package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.Principal;
import com.gimle.core.protocol.CsrPurpose;
import com.gimle.core.protocol.CsrRequestStatus;
import com.gimle.core.protocol.CsrResult;
import com.gimle.core.protocol.CsrSubmission;
import com.gimle.core.protocol.Json;
import com.gimle.holmgang.HolmgangException;
import com.gimle.pki.CertificateSigningRequests;
import com.gimle.pki.Pem;
import com.gimle.pki.Subjects;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

/**
 * Steps driving {@code POST /bootstrap/csr} directly -- the CA bootstrap/signing protocol itself
 * (self-signed CA generation happens once at cluster boot; every scenario here that gets back a
 * usable, chain-verifiable certificate depends on that having worked) -- and the real {@code gimle
 * cert renew --force} rotation-over-mTLS flow.
 */
public final class PkiSteps {

  private static final int KEY_SIZE_BITS = 2048;

  private final ScenarioWorld world;

  public PkiSteps(final ScenarioWorld world) {
    this.world = world;
  }

  @When(
      "a NODE_CLIENT CSR self-declaring organization {string} and common name {string} is"
          + " submitted with a fresh bootstrap token")
  public void aNodeClientCsrSelfDeclaringOrganizationIsSubmitted(
      final String organization, final String commonName) {
    final KeyPair keyPair = generateRsaKeyPair();
    final X500Name subject = new X500Name("O=" + organization + ",CN=" + commonName);
    final PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, subject, List.of("localhost"));
    submitCsr(
        new CsrSubmission(
            CsrPurpose.NODE_CLIENT,
            Pem.encodeCsr(csr),
            Optional.of(world.cluster().mintBootstrapToken())),
        world.cluster().trustOnlyHttpClient());
  }

  @When(
      "a CSR with a mismatched signature is submitted as a NODE_CLIENT request with a fresh"
          + " bootstrap token")
  public void aCsrWithAMismatchedSignatureIsSubmitted() {
    // Builds a CSR whose declared public key does not match the private key that signed it: the
    // structurally-valid-but-cryptographically-bogus case
    // CertificateAuthority#signCertificateRequest
    // must reject before ever trusting the embedded public key.
    final KeyPair declared = generateRsaKeyPair();
    final KeyPair actualSigner = generateRsaKeyPair();
    try {
      final JcaPKCS10CertificationRequestBuilder builder =
          new JcaPKCS10CertificationRequestBuilder(
              new X500Name("CN=tampered-request"), declared.getPublic());
      final ContentSigner signer =
          new JcaContentSignerBuilder("SHA256withRSA").build(actualSigner.getPrivate());
      final PKCS10CertificationRequest tampered = builder.build(signer);
      submitCsr(
          new CsrSubmission(
              CsrPurpose.NODE_CLIENT,
              Pem.encodeCsr(tampered),
              Optional.of(world.cluster().mintBootstrapToken())),
          world.cluster().trustOnlyHttpClient());
    } catch (final Exception e) {
      throw new HolmgangException("failed to build a tampered CSR", e);
    }
  }

  @Then("the CSR submission is rejected with status {int}")
  public void theCsrSubmissionIsRejectedWithStatus(final int expectedStatus) {
    assertEquals(expectedStatus, world.lastCsrSubmissionStatus);
  }

  @Then("the issued certificate organization is {string}, not {string}")
  public void theIssuedCertificateOrganizationIs(
      final String expectedOrganization, final String rejectedOrganization) {
    final Principal principal = Subjects.principalFrom(requireIssuedCertificate());
    assertTrue(
        principal.groups().contains(expectedOrganization),
        "expected organization " + expectedOrganization + " in " + principal.groups());
    assertFalse(
        principal.groups().contains(rejectedOrganization),
        "self-declared organization " + rejectedOrganization + " must never survive signing");
  }

  @Then("the issued certificate lists {string} as a subject alternative name")
  public void theIssuedCertificateListsAsASubjectAlternativeName(final String expectedDnsName)
      throws Exception {
    final Collection<List<?>> sans = requireIssuedCertificate().getSubjectAlternativeNames();
    assertTrue(sans != null, "issued certificate carries no subjectAltName extension at all");
    final boolean found =
        sans.stream()
            .anyMatch(
                entry ->
                    entry.size() == 2
                        && Integer.valueOf(2).equals(entry.get(0)) // GeneralName.dNSName
                        && expectedDnsName.equals(entry.get(1)));
    assertTrue(found, expectedDnsName + " not found among " + sans);
  }

  // ---- own-certificate rotation over mTLS ----

  @When("node {string} renews its own certificate over mTLS via the CLI")
  public void nodeRenewsItsOwnCertificateOverMtlsViaTheCli(final String nodeId) {
    world.originalNodeCertificate =
        decodeCertificateFile(world.cluster().nodeCertificateFile(nodeId));
    world.cluster().renewNodeCertificate(nodeId);
  }

  @Then(
      "the renewed certificate for node {string} keeps its original subject but changes serial"
          + " number")
  public void theRenewedCertificateForNodeKeepsItsOriginalSubjectButChangesSerialNumber(
      final String nodeId) {
    if (world.originalNodeCertificate == null) {
      throw new HolmgangException("no certificate was captured before rotation in this scenario");
    }
    final X509Certificate renewed =
        decodeCertificateFile(world.cluster().nodeCertificateFile(nodeId));
    assertEquals(
        world.originalNodeCertificate.getSubjectX500Principal(), renewed.getSubjectX500Principal());
    assertNotEquals(world.originalNodeCertificate.getSerialNumber(), renewed.getSerialNumber());
  }

  private static X509Certificate decodeCertificateFile(final Path path) {
    try {
      return Pem.decodeCertificate(Files.readString(path, StandardCharsets.US_ASCII));
    } catch (final Exception e) {
      throw new HolmgangException("failed reading certificate file " + path, e);
    }
  }

  private X509Certificate requireIssuedCertificate() {
    if (world.lastIssuedCertificate == null) {
      throw new HolmgangException("no certificate was issued by a CSR submission in this scenario");
    }
    return world.lastIssuedCertificate;
  }

  private void submitCsr(final CsrSubmission submission, final HttpClient client) {
    final HttpResponse<String> response;
    try {
      response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(world.cluster().controlPlaneBaseUrl(0) + "/bootstrap/csr"))
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          Json.write(csrSubmissionToJson(submission)), StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final Exception e) {
      throw new HolmgangException("CSR submission failed", e);
    }
    world.lastCsrSubmissionStatus = response.statusCode();
    if (response.statusCode() != 200) {
      return;
    }
    final CsrResult result = csrResultFromJson(Json.asObject(Json.parse(response.body())));
    if (result.status() == CsrRequestStatus.APPROVED) {
      world.lastIssuedCertificate = Pem.decodeCertificate(result.certificatePem().orElseThrow());
    }
  }

  private static Map<String, Object> csrSubmissionToJson(final CsrSubmission submission) {
    final Map<String, Object> map = new LinkedHashMap<>();
    map.put("purpose", submission.purpose().name());
    map.put("csrPem", submission.csrPem());
    submission.bootstrapToken().ifPresent(token -> map.put("bootstrapToken", token));
    return map;
  }

  private static CsrResult csrResultFromJson(final Map<String, Object> json) {
    final CsrRequestStatus status = CsrRequestStatus.valueOf((String) json.get("status"));
    final Optional<String> requestId = Optional.ofNullable((String) json.get("requestId"));
    final Optional<String> certificatePem =
        Optional.ofNullable((String) json.get("certificatePem"));
    final Optional<String> caCertificatePem =
        Optional.ofNullable((String) json.get("caCertificatePem"));
    return new CsrResult(status, requestId, certificatePem, caCertificatePem);
  }

  private static KeyPair generateRsaKeyPair() {
    try {
      final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(KEY_SIZE_BITS);
      return generator.generateKeyPair();
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA key pair generation unavailable", e);
    }
  }
}
