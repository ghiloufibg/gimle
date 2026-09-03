package com.gimle.andvari;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.andvari.testsupport.InProcessStore;
import com.gimle.andvari.testsupport.TlsTestFixtures;
import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.authz.Permission;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Proves {@code gimle.transport.protocol=tls} actually swaps {@link AndvariServer} onto {@code
 * HttpsServer} with real mTLS, and that the authorization posture holds against real CA-signed
 * client certificates: operators may push, an ungrouped principal is refused by this process's own
 * independent RBAC check, a {@code gimle:nodes} identity may only ever pull, and a forwarded
 * principal from a genuine {@code gimle:controlplane} peer both wins over that peer's own
 * certificate and is re-checked rather than trusted, while the same headers from any other peer are
 * ignored entirely (GIMLE-690) -- plus a real cert-rotation reload, mirroring {@code
 * FafnirServerTlsTest}'s own shape.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-andvari-server-http")
class AndvariServerTlsTest {

  private static final byte[] JAR = "pretend-jar-bytes".getBytes(StandardCharsets.UTF_8);

  @TempDir(cleanup = CleanupMode.NEVER)
  private Path tempDir;

  private TlsTestFixtures tls;
  private CertificateAuthority ca;
  private InProcessStore store;
  private AndvariServer server;

  @BeforeEach
  void setUp() throws Exception {
    tls = new TlsTestFixtures(tempDir);
    ca = TlsTestFixtures.selfSignedCa();
    tls.configureServerTls(ca);
    store = InProcessStore.start(tempDir.resolve("store"));
    server = new AndvariServer(store.client(), 0, tempDir.resolve("data"));
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.close();
    store.close();
    TlsTestFixtures.clearTransportProperties();
  }

  @Test
  @Timeout(10)
  void an_operator_group_certificate_may_push_and_pull() throws Exception {
    HttpClient operator =
        tls.clientWithGroupLeaf(ca, BuiltinRoles.GROUP_OPERATORS, "admin-operator");

    assertEquals(200, send(operator, push("com.example.app", "1.0.0")).statusCode());
    assertEquals(200, send(operator, pull("com.example.app", "1.0.0")).statusCode());
  }

  @Test
  @Timeout(10)
  void an_ungrouped_certificate_is_refused_by_the_independent_rbac_check() throws Exception {
    // A valid, CA-signed certificate -- the mTLS handshake itself succeeds -- but the principal has
    // no group and no role binding in the store, so the Authorizer re-check denies it.
    HttpClient stranger = tls.clientWithLeaf(ca, "stranger");

    assertEquals(403, send(stranger, push("com.example.app", "1.0.0")).statusCode());
    assertEquals(403, send(stranger, pull("com.example.app", "1.0.0")).statusCode());
  }

  @Test
  @Timeout(10)
  void a_nodes_group_certificate_may_pull_only_coordinates_assigned_to_its_node() throws Exception {
    HttpClient operator =
        tls.clientWithGroupLeaf(ca, BuiltinRoles.GROUP_OPERATORS, "admin-operator");
    assertEquals(200, send(operator, push("com.example.app", "1.0.0")).statusCode());

    HttpClient node = tls.clientWithGroupLeaf(ca, BuiltinRoles.GROUP_NODES, "node-1");
    // No assignment for this node yet: even a coordinate that exists in the store is refused --
    // a node identity may only pull what the control plane has actually placed on it.
    assertEquals(403, send(node, pull("com.example.app", "1.0.0")).statusCode());

    store
        .client()
        .propose(
            new StateMutation.PutAssignment(
                new InstanceAssignment(
                    "greeter",
                    0,
                    "node-1",
                    new ModuleId("com.example.app", Version.parse("1.0.0")),
                    "")));

    assertEquals(200, send(node, pull("com.example.app", "1.0.0")).statusCode());
    // Assignment scoping is per-coordinate, not per-node-blanket: a different version stays 403.
    assertEquals(403, send(node, pull("com.example.app", "9.9.9")).statusCode());
    assertEquals(403, send(node, push("com.example.app", "2.0.0")).statusCode());
    assertEquals(
        403,
        send(node, HttpRequest.newBuilder(uri("com.example.app", "1.0.0")).DELETE().build())
            .statusCode());
  }

  /**
   * ADD-10: the control plane's own leaf certificate carried no {@code O=} at all before this fix,
   * so its scheduling-time artifact pull -- unlike a node's, unscoped by assignment, since
   * scheduling needs to resolve whatever coordinate any tenant's manifest references -- fell
   * through to the ordinary RBAC walk with nothing there to ever match, blocking coordinate-only
   * placement indefinitely on a fresh mTLS cluster.
   */
  @Test
  @Timeout(10)
  void a_controlplane_group_certificate_may_pull_any_coordinate_but_never_push_or_delete()
      throws Exception {
    HttpClient operator =
        tls.clientWithGroupLeaf(ca, BuiltinRoles.GROUP_OPERATORS, "admin-operator");
    assertEquals(200, send(operator, push("com.example.app", "1.0.0")).statusCode());

    HttpClient controlPlane =
        tls.clientWithGroupLeaf(ca, BuiltinRoles.GROUP_CONTROLPLANE, "controlplane-1");

    // No assignment or RoleBinding needed at all -- unlike gimle:nodes, unscoped by coordinate.
    assertEquals(200, send(controlPlane, pull("com.example.app", "1.0.0")).statusCode());
    assertEquals(403, send(controlPlane, push("com.example.app", "2.0.0")).statusCode());
    assertEquals(
        403,
        send(controlPlane, HttpRequest.newBuilder(uri("com.example.app", "1.0.0")).DELETE().build())
            .statusCode());
  }

  @Test
  @Timeout(10)
  void a_module_scoped_permission_grants_access_to_only_that_module() throws Exception {
    HttpClient scopedPusher = tls.clientWithLeaf(ca, "scoped-pusher");
    grantRole(
        "app-pusher",
        Set.of(
            Permission.scoped(ResourceKind.ARTIFACT, Verb.WRITE, "com.example.app"),
            Permission.scoped(ResourceKind.ARTIFACT, Verb.READ, "com.example.app")),
        "scoped-pusher");

    assertEquals(200, send(scopedPusher, push("com.example.app", "1.0.0")).statusCode());
    assertEquals(200, send(scopedPusher, pull("com.example.app", "1.0.0")).statusCode());
    // Scoped to exactly one module -- a different module is refused despite the same grant.
    assertEquals(403, send(scopedPusher, push("com.other.app", "1.0.0")).statusCode());
    assertEquals(403, send(scopedPusher, pull("com.other.app", "1.0.0")).statusCode());
  }

  @Test
  @Timeout(10)
  void a_module_scoped_permission_cannot_list_the_full_catalog() throws Exception {
    HttpClient operator =
        tls.clientWithGroupLeaf(ca, BuiltinRoles.GROUP_OPERATORS, "admin-operator");
    assertEquals(200, send(operator, push("com.example.app", "1.0.0")).statusCode());

    HttpClient scopedReader = tls.clientWithLeaf(ca, "scoped-reader");
    grantRole(
        "app-reader",
        Set.of(Permission.scoped(ResourceKind.ARTIFACT, Verb.READ, "com.example.app")),
        "scoped-reader");

    // The scoped grant covers pulling the one module it names...
    assertEquals(200, send(scopedReader, pull("com.example.app", "1.0.0")).statusCode());
    // ...but not the store-wide catalog, which addresses no single module and so only ever
    // matches an unscoped grant.
    assertEquals(
        403,
        send(
                scopedReader,
                HttpRequest.newBuilder(URI.create(baseUrl() + "/artifacts")).GET().build())
            .statusCode());
  }

  @Test
  @Timeout(10)
  void a_tenant_scoped_permission_grants_push_for_a_coordinate_claiming_that_tenant()
      throws Exception {
    HttpClient tenantPusher = tls.clientWithLeaf(ca, "tenant-pusher");
    grantRole(
        "orders-platform-pusher",
        Set.of(Permission.scoped(ResourceKind.ARTIFACT, Verb.WRITE, "orders-platform")),
        "tenant-pusher");

    // The grant is scoped by tenant, not moduleId -- it covers any coordinate this push claims
    // for that tenant, unlike the moduleId-scoped grant above.
    assertEquals(
        200,
        send(tenantPusher, pushWithTenant("com.example.orders", "1.0.0", "orders-platform"))
            .statusCode());
    assertEquals(
        200,
        send(tenantPusher, pushWithTenant("com.example.billing", "1.0.0", "orders-platform"))
            .statusCode());
    // A push naming no tenant at all falls back to moduleId-scoping only, which this principal
    // never holds -- the tenant grant doesn't blanket-cover untenanted pushes.
    assertEquals(403, send(tenantPusher, push("com.example.untenanted", "1.0.0")).statusCode());
  }

  @Test
  @Timeout(10)
  void a_push_cannot_claim_a_tenant_the_caller_holds_no_permission_for() throws Exception {
    // Closes the actual gap this authorization split exists for: holding write permission for
    // "billing" must never let a caller stamp an unrelated tenant onto a brand-new coordinate --
    // the claim itself, not just some existing record, is what gets checked.
    HttpClient billingPusher = tls.clientWithLeaf(ca, "billing-pusher");
    grantRole(
        "billing-pusher",
        Set.of(Permission.scoped(ResourceKind.ARTIFACT, Verb.WRITE, "billing")),
        "billing-pusher");

    assertEquals(
        403,
        send(billingPusher, pushWithTenant("com.example.app", "1.0.0", "orders-platform"))
            .statusCode());
  }

  @Test
  @Timeout(10)
  void reads_and_deletes_check_the_stored_tenant_not_a_caller_supplied_claim() throws Exception {
    HttpClient operator =
        tls.clientWithGroupLeaf(ca, BuiltinRoles.GROUP_OPERATORS, "admin-operator");
    assertEquals(
        200,
        send(operator, pushWithTenant("com.example.app", "1.0.0", "orders-platform")).statusCode());

    HttpClient tenantReader = tls.clientWithLeaf(ca, "orders-platform-reader");
    grantRole(
        "orders-platform-reader",
        Set.of(
            Permission.scoped(ResourceKind.ARTIFACT, Verb.READ, "orders-platform"),
            Permission.scoped(ResourceKind.ARTIFACT, Verb.DELETE, "orders-platform")),
        "orders-platform-reader");

    // Neither request below claims any tenant of its own -- the grant matches because the
    // coordinate's already-stored tenant is what a read/delete checks, not anything the caller
    // asserts (there is nothing to assert on a read in the first place).
    assertEquals(200, send(tenantReader, pull("com.example.app", "1.0.0")).statusCode());
    assertEquals(
        200,
        send(tenantReader, HttpRequest.newBuilder(uri("com.example.app", "1.0.0")).DELETE().build())
            .statusCode());
  }

  private HttpRequest pushWithTenant(String moduleId, String version, String tenantId) {
    return HttpRequest.newBuilder(uri(moduleId, version))
        .header("X-Gimle-Artifact-Tenant", tenantId)
        .PUT(HttpRequest.BodyPublishers.ofByteArray(JAR))
        .build();
  }

  private void grantRole(String roleName, Set<Permission> permissions, String principalName) {
    store.client().propose(new StateMutation.PutRole(new Role(roleName, permissions)));
    store
        .client()
        .propose(
            new StateMutation.PutRoleBinding(
                new RoleBinding(
                    "binding-" + roleName, RoleBinding.userSubject(principalName), roleName)));
  }

  @Test
  @Timeout(10)
  void a_forwarded_principal_from_a_controlplane_peer_wins_and_is_independently_rechecked()
      throws Exception {
    // The peer certificate belongs to gimle:controlplane -- the one group resolvePrincipal trusts
    // to have a forwarded header honored -- and on its own may only ever pull (see
    // a_controlplane_group_certificate_may_pull_any_coordinate_but_never_push_or_delete above), so
    // a push succeeding here proves the forwarded identity, not the peer cert's own limited
    // permission, is what actually got authorized (GIMLE-690).
    HttpClient controlPlanePeer =
        tls.clientWithGroupLeaf(ca, BuiltinRoles.GROUP_CONTROLPLANE, "controlplane-1");
    HttpRequest forwardedOperator =
        HttpRequest.newBuilder(uri("com.example.app", "1.0.0"))
            .header("X-Gimle-Forwarded-Principal", "user:admin")
            .header("X-Gimle-Forwarded-Groups", BuiltinRoles.GROUP_OPERATORS)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(JAR))
            .build();
    assertEquals(200, send(controlPlanePeer, forwardedOperator).statusCode());

    // And the forwarded identity is independently re-checked, not blindly trusted just because the
    // peer is the control plane: a forwarded principal with no permission of its own is still
    // refused.
    HttpRequest forwardedStranger =
        HttpRequest.newBuilder(uri("com.example.app", "2.0.0"))
            .header("X-Gimle-Forwarded-Principal", "user:stranger")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(JAR))
            .build();
    assertEquals(403, send(controlPlanePeer, forwardedStranger).statusCode());
  }

  /**
   * GIMLE-690: {@code SslContexts.forMutualTls} trusts any leaf the shared cluster CA signed, with
   * no per-endpoint allow-list, so a plain, ungrouped certificate can present the same forwarded
   * headers a genuine control-plane proxy hop would. Before the fix, a caller holding any valid
   * cluster leaf -- not only the control plane's own -- could forward {@code gimle:operators} and
   * reach the implicit cluster-admin-equivalent allow. The headers must now be ignored for a
   * non-controlplane peer, falling through to the peer certificate's own (here, absent) permission.
   */
  @Test
  @Timeout(10)
  void a_forwarded_principal_presented_by_a_non_controlplane_peer_is_not_honored()
      throws Exception {
    HttpClient plainPeer = tls.clientWithLeaf(ca, "plain-proxy");
    HttpRequest forwardedOperator =
        HttpRequest.newBuilder(uri("com.example.app", "1.0.0"))
            .header("X-Gimle-Forwarded-Principal", "user:admin")
            .header("X-Gimle-Forwarded-Groups", BuiltinRoles.GROUP_OPERATORS)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(JAR))
            .build();
    assertEquals(403, send(plainPeer, forwardedOperator).statusCode());
  }

  /**
   * {@code B4}: Andvari independently re-checks a presented certificate's serial against the
   * store-backed revocation denylist, the same one {@code gimle-controlplane}'s own {@code
   * ApiServer#resolvePrincipal} and {@code FafnirServer#resolvePrincipal} already check -- it must
   * not trust the CA trust chain alone (unexpired, correctly signed), which a
   * revoked-but-not-yet-expired certificate still satisfies. Before this check existed, an
   * operator's standard incident-response action for a compromised credential -- revoking its
   * certificate -- left Andvari, holding the platform's own artifact catalog, still serving that
   * exact caller.
   */
  @Test
  @Timeout(10)
  void a_revoked_certificate_is_refused_even_though_it_still_holds_the_permission()
      throws Exception {
    TlsTestFixtures.IssuedLeaf operator =
        tls.issueGroupLeafWithCertificate(ca, BuiltinRoles.GROUP_OPERATORS, "revocable-operator");
    HttpClient client = operator.client();

    // Accepted before revocation -- the cert is genuinely valid and the group grants it.
    assertEquals(200, send(client, push("com.example.app", "1.0.0")).statusCode());

    String serial = certificateSerial(operator.certificate());
    store.client().propose(new StateMutation.PutCertificateRevocation(serial, true));

    // Refused after revocation with the identical cert/key and the identical group membership
    // still in place -- proving this is the revocation check, not a permission change.
    assertEquals(401, send(client, pull("com.example.app", "1.0.0")).statusCode());
  }

  /**
   * The forwarded-principal path shares the identical revocation gate: a revoked control-plane
   * proxy certificate must not be allowed to keep vouching for a forwarded claim just because
   * resolution would otherwise fall through to trusting the raw certificate identity unchecked.
   */
  @Test
  @Timeout(10)
  void a_forwarded_principal_via_a_revoked_control_plane_certificate_is_refused() throws Exception {
    TlsTestFixtures.IssuedLeaf controlPlanePeer =
        tls.issueGroupLeafWithCertificate(
            ca, BuiltinRoles.GROUP_CONTROLPLANE, "revocable-controlplane");
    HttpClient client = controlPlanePeer.client();
    HttpRequest forwardedOperator =
        HttpRequest.newBuilder(uri("com.example.app", "1.0.0"))
            .header("X-Gimle-Forwarded-Principal", "user:admin")
            .header("X-Gimle-Forwarded-Groups", BuiltinRoles.GROUP_OPERATORS)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(JAR))
            .build();
    assertEquals(200, send(client, forwardedOperator).statusCode());

    String serial = certificateSerial(controlPlanePeer.certificate());
    store.client().propose(new StateMutation.PutCertificateRevocation(serial, true));

    HttpRequest forwardedOperatorAgain =
        HttpRequest.newBuilder(uri("com.example.app", "2.0.0"))
            .header("X-Gimle-Forwarded-Principal", "user:admin")
            .header("X-Gimle-Forwarded-Groups", BuiltinRoles.GROUP_OPERATORS)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(JAR))
            .build();
    assertEquals(401, send(client, forwardedOperatorAgain).statusCode());
  }

  @Test
  @Timeout(10)
  void reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_server()
      throws Exception {
    Path certFile = Path.of(System.getProperty(TlsTestFixtures.CERT_FILE_PROPERTY));
    Path keyFile = Path.of(System.getProperty(TlsTestFixtures.KEY_FILE_PROPERTY));
    Path caFile = Path.of(System.getProperty(TlsTestFixtures.CA_FILE_PROPERTY));
    TlsSettings clientSettings = new TlsSettings(certFile, keyFile, caFile);
    HttpClient before =
        HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(clientSettings)).build();
    assertEquals(
        200,
        before
            .send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .statusCode());

    // Rotate: a fresh CA-signed leaf, written over the *same* cert/key file paths -- exactly what
    // a real rotation does to gimle.tls.certFile/keyFile in place.
    KeyPair rotatedKeyPair = TlsTestFixtures.generateRsaKeyPair();
    PKCS10CertificationRequest rotatedCsr =
        CertificateSigningRequests.generate(
            rotatedKeyPair, new X500Name("CN=andvari"), List.of("localhost"));
    X509Certificate rotatedLeaf = ca.signCertificateRequest(rotatedCsr, Duration.ofDays(1));
    tls.overwritePem(certFile, "CERTIFICATE", rotatedLeaf.getEncoded());
    tls.overwritePem(keyFile, "PRIVATE KEY", rotatedKeyPair.getPrivate().getEncoded());

    server.reloadTlsMaterial();

    // A brand-new connection (not the already-established one from before rotation) must succeed
    // against the reloaded listener, at the *same* port, without restarting the process.
    HttpClient after =
        HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(clientSettings)).build();
    assertEquals(
        200,
        after
            .send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .statusCode());
  }

  private String baseUrl() {
    return "https://localhost:" + server.port();
  }

  private URI uri(String moduleId, String version) {
    return URI.create(baseUrl() + "/artifacts/" + moduleId + "/" + version);
  }

  private HttpRequest push(String moduleId, String version) {
    return HttpRequest.newBuilder(uri(moduleId, version))
        .PUT(HttpRequest.BodyPublishers.ofByteArray(JAR))
        .build();
  }

  private HttpRequest pull(String moduleId, String version) {
    return HttpRequest.newBuilder(uri(moduleId, version)).GET().build();
  }

  private static HttpResponse<String> send(HttpClient client, HttpRequest request)
      throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static String certificateSerial(X509Certificate certificate) {
    return certificate.getSerialNumber().toString(16).toLowerCase(Locale.ROOT);
  }
}
