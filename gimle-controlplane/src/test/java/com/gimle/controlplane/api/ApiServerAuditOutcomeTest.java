package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.AuditOutcome;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code GOV-6}: a deployment write's audit entry must reflect the real admission outcome, not just
 * whether RBAC/authorization allowed the attempt -- an authorized write that the tenant-quota
 * plugin goes on to reject must record {@link AuditOutcome#REJECTED}, never default to {@link
 * AuditOutcome#APPLIED} just because the caller was allowed to try. Both scenarios (rejected,
 * accepted) run against the one {@link ApiServer}/{@link InProcessStore} pair this class's own
 * {@code @BeforeEach} builds, in a single {@code @Test} rather than one apiece.
 */
class ApiServerAuditOutcomeTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private ApiServer server;
  private HttpClient client;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client());
    server.start();
    baseUrl = "http://localhost:" + server.port();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopServer() {
    server.close();
    inProcessFafnir.close();
    inProcessStore.close();
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /** {@code TestModuleBuilder.minimalDescriptor} fixes the request at 16Mi memory / 10m cpu. */
  private Path buildFixtureJar(String uniqueName) {
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private static String tenantJson(long maxMemoryBytes, long maxCpuMillicores, int maxInstances) {
    return """
        {"quota":{"maxMemoryBytes":%d,"maxCpuMillicores":%d,"maxInstances":%d}}
        """
        .formatted(maxMemoryBytes, maxCpuMillicores, maxInstances);
  }

  private static String tenantedDeploymentYaml(
      String name, String artifactPath, String moduleName, String tenantId) {
    return """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: 1
        tenantId: %s
        """
        .formatted(name, moduleName, artifactPath, tenantId);
  }

  private List<AuditEvent> deploymentWriteAuditEventsFor(String tenantId) {
    return inProcessStore
        .client()
        .listAuditEvents(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())
        .stream()
        .filter(
            e ->
                e.resourceKind().equals("DEPLOYMENT")
                    && e.verb().equals("WRITE")
                    && e.tenantId().equals(Optional.of(tenantId)))
        .toList();
  }

  private List<AuditEvent> tenantWriteAuditEventsFor(String tenantId) {
    return inProcessStore
        .client()
        .listAuditEvents(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())
        .stream()
        .filter(
            e ->
                e.resourceKind().equals("TENANT")
                    && e.verb().equals("WRITE")
                    && e.tenantId().equals(Optional.of(tenantId)))
        .toList();
  }

  /**
   * {@code B1}: a tenant-creation write plaintext mode goes on to refuse must record {@link
   * AuditOutcome#REJECTED} in its own audit entry, not default to {@link AuditOutcome#APPLIED} the
   * way {@link #requireAuthorized} would have recorded it before {@link
   * ApiServer#rejectSecondTenantUnderPlaintext} ever got a chance to run. Before the fix, the audit
   * trail for a refused second-tenant creation was byte-for-byte indistinguishable from a genuine
   * success -- {@code allowed:true, outcome:APPLIED} either way -- which is exactly what an audit
   * trail exists to prevent.
   */
  @Test
  void a_tenant_creation_refused_under_plaintext_records_rejected_not_applied() throws Exception {
    // A first real tenant, so plaintext's own single-real-tenant rule has something to refuse a
    // second one against (see rejectSecondTenantUnderPlaintext's own javadoc: neither the
    // reserved-system nor the default tenant counts toward this).
    HttpResponse<String> firstTenantPut =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/audit-b1-tenant-a"))
                .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1, 1, 1)))
                .build());
    assertEquals(200, firstTenantPut.statusCode());

    HttpResponse<String> refusedSecondTenantPut =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/audit-b1-tenant-b"))
                .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1, 1, 1)))
                .build());
    assertEquals(403, refusedSecondTenantPut.statusCode());

    // The refused id was never actually created...
    HttpResponse<String> getRefused =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/audit-b1-tenant-b")).build());
    assertEquals(404, getRefused.statusCode());

    // ...and its own audit trail entry must say so: REJECTED, never APPLIED, even though
    // plaintext mode's own RBAC carve-out did allow the attempt to reach admission at all.
    List<AuditEvent> refusedEvents = tenantWriteAuditEventsFor("audit-b1-tenant-b");
    assertEquals(1, refusedEvents.size());
    assertTrue(refusedEvents.get(0).allowed(), "plaintext mode's RBAC carve-out did allow this");
    assertEquals(AuditOutcome.REJECTED, refusedEvents.get(0).outcome());

    // A genuine success -- updating the tenant that was actually created -- still records
    // APPLIED, so the fix doesn't just flip every tenant write to REJECTED.
    HttpResponse<String> genuineUpdate =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/audit-b1-tenant-a"))
                .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(2, 2, 2)))
                .build());
    assertEquals(200, genuineUpdate.statusCode());
    List<AuditEvent> appliedEvents = tenantWriteAuditEventsFor("audit-b1-tenant-a");
    assertEquals(2, appliedEvents.size(), "the initial create plus this update");
    assertTrue(appliedEvents.stream().allMatch(e -> e.outcome() == AuditOutcome.APPLIED));
  }

  @Test
  void a_deployment_writes_audit_outcome_matches_the_real_admission_result_not_just_rbac()
      throws Exception {
    // Same tenant id throughout, quota raised between the two deployments below -- plaintext mode
    // permits only one real tenant at a time, and this test's actual point (REJECTED vs. APPLIED
    // audit outcome) doesn't need a second tenant identity, only a second quota.
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/audit-outcome-tenant"))
            .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1, 1, 1)))
            .build());

    // Rejected: authorized (plaintext mode always allows), then rejected by the tenant-quota
    // plugin -- the audit trail must say REJECTED, not default to APPLIED just because RBAC said
    // yes.
    Path overQuotaJar = buildFixtureJar("com.gimle.fixture.auditoutcome.over");
    HttpResponse<String> rejectedPut =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/over-quota"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        tenantedDeploymentYaml(
                            "over-quota",
                            overQuotaJar.toAbsolutePath().toString(),
                            "com.gimle.fixture.auditoutcome.over",
                            "audit-outcome-tenant")))
                .build());
    assertEquals(409, rejectedPut.statusCode());
    List<AuditEvent> rejectedEvents = deploymentWriteAuditEventsFor("audit-outcome-tenant");
    assertEquals(1, rejectedEvents.size());
    assertTrue(rejectedEvents.get(0).allowed(), "RBAC did allow the attempt");
    assertEquals(AuditOutcome.REJECTED, rejectedEvents.get(0).outcome());

    // Accepted: same tenant, quota raised, authorized and admitted -- the audit trail must say
    // APPLIED.
    send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/audit-outcome-tenant"))
            .PUT(HttpRequest.BodyPublishers.ofString(tenantJson(1_000_000_000L, 4000, 10)))
            .build());
    Path withinQuotaJar = buildFixtureJar("com.gimle.fixture.auditoutcome.within");
    HttpResponse<String> acceptedPut =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/within-quota"))
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        tenantedDeploymentYaml(
                            "within-quota",
                            withinQuotaJar.toAbsolutePath().toString(),
                            "com.gimle.fixture.auditoutcome.within",
                            "audit-outcome-tenant")))
                .build());
    assertEquals(200, acceptedPut.statusCode());
    List<AuditEvent> acceptedEvents =
        deploymentWriteAuditEventsFor("audit-outcome-tenant").stream()
            .filter(e -> e.outcome() == AuditOutcome.APPLIED)
            .toList();
    assertEquals(1, acceptedEvents.size());
    assertEquals(AuditOutcome.APPLIED, acceptedEvents.get(0).outcome());
  }
}
