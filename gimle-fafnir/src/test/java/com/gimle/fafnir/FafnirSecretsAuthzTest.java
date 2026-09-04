package com.gimle.fafnir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.authz.Permission;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.AuditOutcome;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.core.tls.SslContexts;
import com.gimle.fafnir.testsupport.InProcessStore;
import com.gimle.fafnir.testsupport.TlsTestFixtures;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StateStore;
import com.gimle.pki.CertificateAuthority;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Fafnir runs its own, independent {@code Authorizer.authorize(...)} check on {@code /secrets/*}
 * rather than trusting "this request arrived already-forwarded by gimle-controlplane" as proof of
 * authorization by itself. Every test here talks to {@link FafnirServer} directly over real mTLS --
 * no {@code ApiServer} in the loop -- to prove Fafnir's own gate works standalone, including the
 * specific scenario a buggy or compromised proxy would exploit: a forwarded-principal header naming
 * someone who does not actually hold the permission.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-fafnir-server-http")
class FafnirSecretsAuthzTest {

  private static final String FORWARDED_PRINCIPAL_HEADER = "X-Gimle-Forwarded-Principal";
  private static final String FORWARDED_GROUPS_HEADER = "X-Gimle-Forwarded-Groups";

  @TempDir Path tempDir;

  private TlsTestFixtures tls;

  @BeforeEach
  void setUp() {
    tls = new TlsTestFixtures(tempDir);
  }

  @AfterEach
  void clearTransportProperties() {
    TlsTestFixtures.clearTransportProperties();
  }

  @Test
  @Timeout(10)
  void a_caller_whose_own_certificate_holds_no_secret_permission_is_forbidden() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      // No Role/RoleBinding granted to "caller" at all -- an authenticated, CA-signed identity
      // with zero RBAC permissions, the plain "not authorized" case.
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_caller_whose_own_certificate_holds_the_permission_succeeds() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      grantSecretReadAndWrite(inProcessStore.store(), "caller");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_forwarded_principal_who_actually_holds_the_permission_succeeds() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      grantSecretReadAndWrite(inProcessStore.store(), "alice");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        // "proxy" here presents a genuine gimle:controlplane leaf (an identity with no SECRET
        // permission of its own) but forwards a claim naming "alice", the real, authorized
        // principal -- exactly the shape gimle-controlplane's own proxy hop uses, and the one peer
        // identity resolvePrincipal is allowed to trust a forwarded header from (GIMLE-690).
        HttpClient client = tls.controlPlaneClientWithLeaf(ca, "controlplane-proxy");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .header(FORWARDED_PRINCIPAL_HEADER, "alice")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
      }
    }
  }

  /**
   * {@code B4}: Fafnir independently re-checks a presented certificate's serial against the
   * store-backed revocation denylist, the same one {@code gimle-controlplane}'s own {@code
   * ApiServer#resolvePrincipal} already checks -- it must not trust the CA trust chain alone
   * (unexpired, correctly signed), which a revoked-but-not-yet-expired certificate still satisfies.
   * Before this check existed, an operator's standard incident-response action for a compromised
   * credential -- revoking its certificate -- left Fafnir, the process holding the platform's most
   * sensitive data, still returning live plaintext for that exact caller.
   */
  @Test
  @Timeout(10)
  void a_revoked_certificate_is_refused_even_though_it_still_holds_the_permission()
      throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      grantSecretReadAndWrite(inProcessStore.store(), "caller");
      TlsTestFixtures.IssuedLeaf leaf = tls.issueLeafWithCertificate(ca, "caller");
      HttpClient client = leaf.client();
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        String secretsUrl = "https://localhost:" + server.port() + "/secrets/acme";

        // Accepted before revocation -- the cert is genuinely valid and the caller genuinely
        // holds the permission.
        assertEquals(
            200,
            client
                .send(
                    HttpRequest.newBuilder(URI.create(secretsUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .statusCode());

        String serial = certificateSerial(leaf.certificate());
        inProcessStore.client().propose(new StateMutation.PutCertificateRevocation(serial, true));

        // Refused after revocation with the identical cert/key and the identical RBAC grant
        // still in place -- proving this is the revocation check, not a permission change.
        assertEquals(
            401,
            client
                .send(
                    HttpRequest.newBuilder(URI.create(secretsUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .statusCode());
      }
    }
  }

  /**
   * The forwarded-principal path shares the identical revocation gate: a revoked control-plane
   * proxy certificate must not be allowed to keep vouching for a forwarded claim just because
   * resolution would otherwise fall through to trusting the raw certificate identity unchecked.
   */
  @Test
  @Timeout(10)
  void a_forwarded_principal_via_a_revoked_control_plane_certificate_is_refused() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      grantSecretReadAndWrite(inProcessStore.store(), "alice");
      TlsTestFixtures.IssuedLeaf proxyLeaf =
          tls.issueControlPlaneLeafWithCertificate(ca, "controlplane-proxy");
      HttpClient client = proxyLeaf.client();
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpRequest forwardedRequest =
            HttpRequest.newBuilder(
                    URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                .header(FORWARDED_PRINCIPAL_HEADER, "alice")
                .GET()
                .build();

        assertEquals(
            200,
            client
                .send(forwardedRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .statusCode());

        String serial = certificateSerial(proxyLeaf.certificate());
        inProcessStore.client().propose(new StateMutation.PutCertificateRevocation(serial, true));

        assertEquals(
            401,
            client
                .send(forwardedRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .statusCode());
      }
    }
  }

  private static String certificateSerial(X509Certificate certificate) {
    return certificate.getSerialNumber().toString(16).toLowerCase(Locale.ROOT);
  }

  @Test
  @Timeout(10)
  void a_forwarded_principal_who_does_not_actually_hold_the_permission_is_still_forbidden()
      throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      // "mallory" is never granted anything -- a genuine gimle:controlplane proxy claims to be
      // forwarding an authorized request on her behalf; Fafnir's own independent RBAC read still
      // finds no grant and denies it, proving the proxy's own (missing, in this test) authz check
      // is not what's actually protecting this endpoint: Fafnir never trusts "arrived
      // already-forwarded" as proof of authorization by itself.
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.controlPlaneClientWithLeaf(ca, "controlplane-proxy");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .header(FORWARDED_PRINCIPAL_HEADER, "mallory")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  /**
   * GIMLE-690: any cluster leaf certificate -- not only the control plane's own -- can present the
   * {@code X-Gimle-Forwarded-Principal}/{@code -Groups} headers, since {@code
   * SslContexts.forMutualTls} trusts any leaf the shared cluster CA signed with no per-endpoint
   * allow-list. Before the fix, a caller holding a plain {@code gimle:nodes} certificate could dial
   * Fafnir directly and forward {@code root}/{@code gimle:operators} to reach {@code
   * Authorizer.authorize}'s unconditional cluster-admin short-circuit. Presenting the exact same
   * headers here must instead fall through to the node's own self-service check, which denies a
   * node with no assignment for this tenant.
   */
  @Test
  @Timeout(10)
  void a_forwarded_principal_presented_by_a_non_controlplane_peer_is_not_honored()
      throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      // "node-1" holds no assignment for "acme" at all -- were the forwarded headers honored, the
      // claimed root/gimle:operators identity would bypass RBAC entirely and this would be a 200.
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.nodeClientWithLeaf(ca, "node-1");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .header(FORWARDED_PRINCIPAL_HEADER, "root")
                    .header(FORWARDED_GROUPS_HEADER, "gimle:operators")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void an_unauthenticated_plaintext_request_is_allowed_matching_every_other_gimle_process()
      throws Exception {
    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_node_with_an_active_assignment_for_the_tenant_may_read_its_secrets() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      assignDeploymentToNode(inProcessStore.store(), "node-1", "acme");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.nodeClientWithLeaf(ca, "node-1");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_node_with_no_assignment_for_the_tenant_is_forbidden_regardless_of_key() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      // "node-1" is assigned to a deployment for a different tenant -- proves this is a genuine
      // per-tenant check, not merely "does this node have any assignment at all."
      assignDeploymentToNode(inProcessStore.store(), "node-1", "other-tenant");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.nodeClientWithLeaf(ca, "node-1");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_node_may_never_write_a_secret_even_with_an_active_assignment() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      assignDeploymentToNode(inProcessStore.store(), "node-1", "acme");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.nodeClientWithLeaf(ca, "node-1");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://localhost:" + server.port() + "/secrets/acme/db-password"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"aGVsbG8=\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void the_bulk_value_read_runs_its_own_authorizer_check_and_forbids_an_ungranted_caller()
      throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      // No Role/RoleBinding at all -- the same "authenticated but ungranted" caller the
      // single-key read is refused for, refused identically on the bulk route.
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://localhost:" + server.port() + "/secrets/acme?names=a,b"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void the_bulk_value_read_is_allowed_for_a_caller_holding_secret_read() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      grantSecretReadAndWrite(inProcessStore.store(), "caller");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://localhost:" + server.port() + "/secrets/acme?names=a,b"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
      }
    }
  }

  /**
   * The bulk route must never widen what a node identity can do: a node with a live assignment for
   * the tenant is allowed to read its secrets one key at a time (see the test above), and that
   * self-service path deliberately does not extend to "hand me every named secret this tenant owns
   * in one response," which is an operator migration tool rather than anything a node needs.
   */
  @Test
  @Timeout(10)
  void a_node_is_forbidden_from_the_bulk_value_read_even_with_an_active_assignment()
      throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      assignDeploymentToNode(inProcessStore.store(), "node-1", "acme");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.nodeClientWithLeaf(ca, "node-1");

        HttpResponse<String> singleKey =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> bulk =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://localhost:"
                                + server.port()
                                + "/secrets/acme?names=db-password"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, singleKey.statusCode());
        assertEquals(403, bulk.statusCode());
      }
    }
  }

  /**
   * Allowed and denied {@code /secrets/*} decisions both land in the durable audit trail alongside
   * Fafnir's own existing SLF4J log line.
   */
  @Test
  @Timeout(10)
  void an_allowed_secret_request_is_recorded_in_the_durable_audit_trail() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      grantSecretReadAndWrite(inProcessStore.store(), "caller");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());

        var events =
            inProcessStore
                .store()
                .listAuditEvents(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(1, events.size());
        assertEquals("caller", events.get(0).principal());
        assertEquals("SECRET", events.get(0).resourceKind());
        assertEquals("READ", events.get(0).verb());
        assertEquals(Optional.of("acme"), events.get(0).tenantId());
        assertEquals(true, events.get(0).allowed());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_denied_secret_request_is_recorded_in_the_durable_audit_trail() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      // No Role/RoleBinding granted -- the plain "not authorized" case, same as this file's own
      // a_caller_whose_own_certificate_holds_no_secret_permission_is_forbidden test.
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(403, response.statusCode());

        var events =
            inProcessStore
                .store()
                .listAuditEvents(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(1, events.size());
        assertEquals(false, events.get(0).allowed());
      }
    }
  }

  /**
   * The {@code GROUP_NODES} self-service branch (see {@link FafnirServer#authorizeSecrets})
   * bypasses {@code authorizer.authorize(...)} entirely, but still computes an {@code allowed}
   * boolean that belongs in the trail exactly like an ordinary caller's decision does.
   */
  @Test
  @Timeout(10)
  void a_nodes_self_service_secret_read_is_also_audited() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      assignDeploymentToNode(inProcessStore.store(), "node-1", "acme");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.nodeClientWithLeaf(ca, "node-1");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/acme"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());

        var events =
            inProcessStore
                .store()
                .listAuditEvents(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(1, events.size());
        assertEquals("node-1", events.get(0).principal());
        assertEquals(true, events.get(0).allowed());
      }
    }
  }

  // ---- soft-delete vs. hard destroy (?destroy=true) audit distinctness (GIMLE-693) ----
  //
  // A soft delete and a hard destroy both authorize under the identical Verb.DELETE, but only a
  // hard destroy is irreversible -- the trail must be able to tell them apart, and a subsequent
  // access attempt against a destroyed key must still show up as an ordinary audited access.

  @Test
  @Timeout(10)
  void a_soft_delete_is_recorded_in_the_durable_audit_trail_as_applied() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      grantSecretReadAndWrite(inProcessStore.store(), "caller");
      inProcessStore.store().putTenant(new Tenant("acme", new ResourceQuota(1, 1, 1)));
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.clientWithLeaf(ca, "caller");
        String secretUrl = "https://localhost:" + server.port() + "/secrets/acme/db-password";
        putSecret(client, secretUrl, "hunter2");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(URI.create(secretUrl)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());

        AuditEvent deleteEvent = onlyEventWithVerb(inProcessStore.store(), "SECRET", "DELETE");
        assertEquals("caller", deleteEvent.principal());
        assertEquals(Optional.of("db-password"), deleteEvent.targetId());
        assertEquals(true, deleteEvent.allowed());
        assertEquals(AuditOutcome.APPLIED, deleteEvent.outcome());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_hard_destroy_is_recorded_in_the_durable_audit_trail_as_destroyed() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      grantSecretReadAndWrite(inProcessStore.store(), "caller");
      inProcessStore.store().putTenant(new Tenant("acme", new ResourceQuota(1, 1, 1)));
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.clientWithLeaf(ca, "caller");
        String secretUrl = "https://localhost:" + server.port() + "/secrets/acme/db-password";
        putSecret(client, secretUrl, "hunter2");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(URI.create(secretUrl + "?destroy=true")).DELETE().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());

        AuditEvent destroyEvent = onlyEventWithVerb(inProcessStore.store(), "SECRET", "DELETE");
        assertEquals("caller", destroyEvent.principal());
        assertEquals(Optional.of("db-password"), destroyEvent.targetId());
        assertEquals(true, destroyEvent.allowed());
        assertEquals(AuditOutcome.DESTROYED, destroyEvent.outcome());
      }
    }
  }

  /**
   * The one follow-up check the QA report specifically called out: a request against a key that was
   * just hard-destroyed must not silently vanish from the trail -- it is audited exactly like any
   * other now-failing, not-found access, since the authorization decision (and its own audit entry)
   * happens before {@link FafnirServer} ever looks at whether the key still has data.
   */
  @Test
  @Timeout(10)
  void a_read_after_a_hard_destroy_is_still_audited_as_a_not_found_access() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      grantSecretReadAndWrite(inProcessStore.store(), "caller");
      inProcessStore.store().putTenant(new Tenant("acme", new ResourceQuota(1, 1, 1)));
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.clientWithLeaf(ca, "caller");
        String secretUrl = "https://localhost:" + server.port() + "/secrets/acme/db-password";
        putSecret(client, secretUrl, "hunter2");
        client.send(
            HttpRequest.newBuilder(URI.create(secretUrl + "?destroy=true")).DELETE().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        HttpResponse<String> getResponse =
            client.send(
                HttpRequest.newBuilder(URI.create(secretUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(404, getResponse.statusCode());

        HttpResponse<String> putResponse = putSecret(client, secretUrl, "hunter3");
        assertEquals(200, putResponse.statusCode());

        AuditEvent readEvent = onlyEventWithVerb(inProcessStore.store(), "SECRET", "READ");
        assertEquals("caller", readEvent.principal());
        assertEquals(Optional.of("db-password"), readEvent.targetId());
        assertEquals(true, readEvent.allowed());
        assertEquals(AuditOutcome.APPLIED, readEvent.outcome());

        List<AuditEvent> writeEvents = eventsWithVerb(inProcessStore.store(), "SECRET", "WRITE");
        assertEquals(2, writeEvents.size());
      }
    }
  }

  // ---- /secrets/rotate-key, /secrets/retire-key (GIMLE-692) ----
  //
  // Both routes are cluster-wide, non-tenant-scoped admin operations -- FafnirServer's own
  // authorizeGlobalSecretsAdmin gate, not the tenant-scoped authorizeSecrets every /secrets/
  // {tenantId}/... route above goes through. Mirrors this file's own established pattern: a
  // no-permission caller is forbidden, a caller actually holding the (unscoped) SECRET/WRITE grant
  // succeeds, and -- the scenario unique to these two routes -- a caller presenting no client
  // certificate at all, with no forwarded header or session cookie either, is unauthorized rather
  // than silently treated as permitted.

  @Test
  @Timeout(10)
  void a_rotate_key_request_from_a_caller_with_no_secret_write_permission_is_forbidden()
      throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      // No Role/RoleBinding granted to "caller" at all, same as this file's own
      // a_caller_whose_own_certificate_holds_no_secret_permission_is_forbidden test.
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/rotate-key"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_retire_key_request_from_a_caller_with_no_secret_write_permission_is_forbidden()
      throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/retire-key"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"keyId\":1}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(403, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_rotate_key_request_from_a_caller_with_the_permission_succeeds() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      grantSecretReadAndWrite(inProcessStore.store(), "caller");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/rotate-key"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_retire_key_request_from_a_caller_with_the_permission_succeeds() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      grantSecretReadAndWrite(inProcessStore.store(), "caller");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.clientWithLeaf(ca, "caller");
        String rotateUrl = "https://localhost:" + server.port() + "/secrets/rotate-key";
        // Rotate twice first (active key id 0 -> 1 -> 2) so key id 1 is retireable: #retire
        // rejects retiring whichever id is currently active.
        client.send(
            HttpRequest.newBuilder(URI.create(rotateUrl))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        client.send(
            HttpRequest.newBuilder(URI.create(rotateUrl))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/retire-key"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"keyId\":1}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
      }
    }
  }

  /**
   * The gap the QA report flagged directly: neither rotate nor retire ever showed up in the audit
   * trail, because both were recorded under the generic, tenant-scoped {@code SECRET} kind
   * indistinguishable from an ordinary secret write -- worse, both routes recorded {@code WRITE}
   * even though retirement is the destructive counterpart to rotation, not a write. Both now land
   * under {@link ResourceKind#SECRETS_KEY}, with distinct verbs, and each entry names the key id
   * its own operation actually touched.
   */
  @Test
  @Timeout(10)
  void a_successful_rotate_key_request_is_recorded_in_the_durable_audit_trail() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      grantSecretReadAndWrite(inProcessStore.store(), "caller");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.clientWithLeaf(ca, "caller");

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/rotate-key"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());

        List<AuditEvent> events =
            inProcessStore
                .store()
                .listAuditEvents(
                    Optional.empty(),
                    Optional.of("SECRETS_KEY"),
                    Optional.empty(),
                    Optional.empty());
        assertEquals(1, events.size());
        assertEquals("caller", events.get(0).principal());
        assertEquals("WRITE", events.get(0).verb());
        assertEquals(Optional.empty(), events.get(0).tenantId());
        assertEquals(Optional.of("1"), events.get(0).targetId());
        assertEquals(true, events.get(0).allowed());
        assertEquals(AuditOutcome.APPLIED, events.get(0).outcome());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_successful_retire_key_request_is_recorded_in_the_durable_audit_trail() throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      grantSecretReadAndWrite(inProcessStore.store(), "caller");
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = tls.clientWithLeaf(ca, "caller");
        String rotateUrl = "https://localhost:" + server.port() + "/secrets/rotate-key";
        client.send(
            HttpRequest.newBuilder(URI.create(rotateUrl))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        client.send(
            HttpRequest.newBuilder(URI.create(rotateUrl))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/retire-key"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"keyId\":1}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());

        List<AuditEvent> events =
            inProcessStore
                .store()
                .listAuditEvents(
                    Optional.empty(),
                    Optional.of("SECRETS_KEY"),
                    Optional.empty(),
                    Optional.empty());
        // Two WRITE entries from the two rotations above, plus this retirement's own DELETE --
        // rotate and retire are now distinguishable by verb within the same resource kind.
        assertEquals(3, events.size());
        AuditEvent retireEvent =
            events.stream().filter(e -> "DELETE".equals(e.verb())).findFirst().orElseThrow();
        assertEquals("caller", retireEvent.principal());
        assertEquals(Optional.empty(), retireEvent.tenantId());
        assertEquals(Optional.of("1"), retireEvent.targetId());
        assertEquals(true, retireEvent.allowed());
        assertEquals(AuditOutcome.APPLIED, retireEvent.outcome());
      }
    }
  }

  /**
   * The scenario distinct from "authenticated but not permitted": a caller that never identifies
   * itself at all -- no client certificate presented (Fafnir's server socket only ever {@code
   * wantClientAuth}s, never {@code needClientAuth}s, so the handshake itself still completes), no
   * forwarded-principal header, no session cookie. {@link FafnirServer#authorizeGlobalSecretsAdmin}
   * must reject this as 401, the same "authentication required" outcome {@code
   * authorizeSecrets}-gated routes already give a principal-less caller.
   */
  @Test
  @Timeout(10)
  void a_rotate_key_request_with_no_principal_information_at_all_is_unauthorized()
      throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = trustOnlyClient(ca);

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/rotate-key"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(401, response.statusCode());
      }
    }
  }

  @Test
  @Timeout(10)
  void a_retire_key_request_with_no_principal_information_at_all_is_unauthorized()
      throws Exception {
    CertificateAuthority ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);

    try (InProcessStore inProcessStore = InProcessStore.start(tempDir.resolve("store"))) {
      FafnirCrypto crypto =
          new FafnirCrypto(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
      try (FafnirServer server = new FafnirServer(crypto, 0)) {
        server.start();
        HttpClient client = trustOnlyClient(ca);

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("https://localhost:" + server.port() + "/secrets/retire-key"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"keyId\":1}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(401, response.statusCode());
      }
    }
  }

  /** An {@link HttpClient} trusting {@code ca} but presenting no client certificate of its own. */
  private HttpClient trustOnlyClient(CertificateAuthority ca) throws Exception {
    Path caFile = tls.writePem("trust-only-ca.pem", "CERTIFICATE", ca.certificate().getEncoded());
    return HttpClient.newBuilder().sslContext(SslContexts.forServerTrustOnly(caFile)).build();
  }

  /**
   * A single-replica deployment placed on {@code nodeId} for {@code tenantId} -- the minimal
   * scheduler-decision shape {@link FafnirServer#isTenantAssignedToNode} joins against, mirroring
   * exactly what a real {@code DeploymentReconciler} placement would have written.
   */
  private static void assignDeploymentToNode(StateStore store, String nodeId, String tenantId) {
    ModuleId moduleId = new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));
    store.putDeployment(
        new DeploymentSpec(
            "dep-" + tenantId,
            moduleId,
            "/var/gimle/artifacts/orders-1.0.0.jar",
            1,
            PlacementConstraints.NONE,
            Optional.empty(),
            Optional.of(tenantId)));
    store.putAssignment(
        new InstanceAssignment(
            "dep-" + tenantId,
            0,
            nodeId,
            moduleId,
            "/var/gimle/artifacts/orders-1.0.0.jar",
            OptionalInt.empty(),
            Optional.of(tenantId)));
  }

  private static void grantSecretReadAndWrite(StateStore store, String username) {
    store.putRole(
        new Role(
            "secret-rw",
            Set.of(
                Permission.unscoped(ResourceKind.SECRET, Verb.READ),
                Permission.unscoped(ResourceKind.SECRET, Verb.WRITE),
                Permission.unscoped(ResourceKind.SECRET, Verb.DELETE))));
    store.putRoleBinding(
        new RoleBinding("b-" + username, RoleBinding.userSubject(username), "secret-rw"));
  }

  /** Writes {@code plaintext} to {@code secretUrl} as an opaque secret, over {@code client}. */
  private static HttpResponse<String> putSecret(
      HttpClient client, String secretUrl, String plaintext) throws Exception {
    String body =
        "{\"value\":\""
            + Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8))
            + "\"}";
    return client.send(
        HttpRequest.newBuilder(URI.create(secretUrl))
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /**
   * Every durable audit entry under {@code resourceKind} whose own {@code verb} is {@code verb}.
   */
  private static List<AuditEvent> eventsWithVerb(
      StateStore store, String resourceKind, String verb) {
    return store
        .listAuditEvents(
            Optional.empty(), Optional.of(resourceKind), Optional.empty(), Optional.empty())
        .stream()
        .filter(e -> verb.equals(e.verb()))
        .toList();
  }

  /**
   * {@link #eventsWithVerb}, asserting exactly one match -- the common case in this file's tests.
   */
  private static AuditEvent onlyEventWithVerb(StateStore store, String resourceKind, String verb) {
    List<AuditEvent> matches = eventsWithVerb(store, resourceKind, verb);
    assertEquals(1, matches.size());
    return matches.get(0);
  }
}
