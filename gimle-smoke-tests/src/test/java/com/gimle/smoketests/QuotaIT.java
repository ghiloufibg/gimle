package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Multi-tenancy quota enforcement, both halves: {@code QuotaReconciler}'s own documented
 * flag-but-never-evict contract when a tenant's quota is retroactively lowered below what's already
 * running, and {@code ApiServer#checkTenantQuota}'s real 409 rejection *at admission* for a new
 * deployment that would immediately push a tenant over quota (never durably created at all).
 */
@Tag("smoke")
class QuotaIT extends GreeterSmokeClusterSupport {

  /**
   * QA hardening pass, Phase 3 continuation: {@code QuotaReconciler}'s own class javadoc states it
   * deliberately never evicts instances to force compliance, only surfaces a quota violation for a
   * human operator to resolve -- covered at the reconciler-unit tier ({@code QuotaReconcilerTest}),
   * but nothing previously proved this against a real running deployment. A tenant's quota is
   * retroactively lowered below what's already running (the exact scenario that reconciler's own
   * javadoc names), and this asserts both halves: the violation becomes visible on the real API
   * surface, and the already-running instance is never touched.
   */
  @Test
  @Timeout(value = 6, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_tenant_over_quota_deployment_is_flagged_but_not_evicted() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);

    String quotaTenantId = "quota-smoke-tenant";
    // Generous enough for the provider's own request (32Mi/20m, gimle-module.yaml) to schedule
    // and reach ACTIVE cleanly before the quota is lowered below it.
    putTenantQuota(baseUrl, quotaTenantId, 256L * 1024 * 1024, 1000L, 10);

    submitDeployment(
        baseUrl,
        "greeter-provider-deployment",
        "com.gimle.examples.greeter.provider",
        providerJar,
        Optional.of(quotaTenantId));
    await(
        () -> isActive(baseUrl, "greeter-provider-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-deployment should reach ACTIVE under a compliant quota");
    assertTrue(
        !isQuotaViolating(baseUrl, "greeter-provider-deployment"),
        "should not be flagged while comfortably within quota");

    // Retroactively lower the same tenant's quota below what's already running -- QuotaReconciler's
    // own documented trigger for this flag, not a quota rejected at admission time.
    putTenantQuota(baseUrl, quotaTenantId, 1L, 1L, 0);

    await(
        () -> isQuotaViolating(baseUrl, "greeter-provider-deployment"),
        Duration.ofSeconds(30),
        "greeter-provider-deployment should be flagged quota-violating once its tenant's quota is"
            + " retroactively lowered below what's already running");
    // Give the reconciler several more ticks' worth of headroom, then confirm it still never
    // touched the running instance -- the actual guarantee this test exists to prove, not just
    // that the flag can be set.
    Thread.sleep(Duration.ofSeconds(5).toMillis());
    // A single isActive() read can be a false negative under heavy sandbox load (a momentary
    // heartbeat/store-read staleness -- see FLAKY_TESTS.md's own recurring theme -- not a real
    // eviction), so require the reading to recover within a short confirmation window before
    // concluding anything was actually touched: a genuine eviction (the instance actually stopped,
    // never restarted, per QuotaReconciler's own never-evict-never-restart contract) would stay
    // non-ACTIVE throughout this whole window, not just the original single sample.
    boolean confirmedActive = false;
    for (int attempt = 0; attempt < 5 && !confirmedActive; attempt++) {
      confirmedActive = isActive(baseUrl, "greeter-provider-deployment");
      if (!confirmedActive) {
        Thread.sleep(Duration.ofSeconds(1).toMillis());
      }
    }
    assertTrue(
        confirmedActive,
        "a quota-violating deployment must stay untouched, never evicted, per QuotaReconciler's"
            + " own documented contract");
  }

  /**
   * QA Phase 3 continuation: the admission-time counterpart to the flag-but-don't-evict scenario
   * above. {@code ApiServer#checkTenantQuota} is a real, already-implemented 409 rejection at
   * submission time -- distinct from {@code QuotaReconciler}'s own after-the-fact flag for a quota
   * lowered *below* what's already running -- but nothing previously proved it against a real
   * cluster either. A tenant's quota is sized to fit exactly one {@code greeter-provider} replica
   * (32Mi/20m request, see its {@code gimle-module.yaml}) and no more; a second deployment for the
   * same tenant is submitted once the first is already {@code ACTIVE}, and this asserts the
   * rejection is real (409, the deployment never created at all -- {@code GET /deployments/*}
   * returns 404, not an empty/pending record) and that it never touched the first, already-running
   * deployment.
   */
  @Test
  @Timeout(value = 4, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_deployment_that_would_exceed_tenant_quota_is_rejected_at_admission() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);

    String tenantId = "quota-admission-smoke-tenant";
    // Room for exactly one 32Mi/20m replica (the provider's own request) and no more: a second
    // replica of anything would push memory to 64Mi (> 40Mi) and instances to 2 (> 1).
    putTenantQuota(baseUrl, tenantId, 40L * 1024 * 1024, 1000L, 1);

    submitDeployment(
        baseUrl,
        "greeter-provider-quota-admission-a",
        "com.gimle.examples.greeter.provider",
        providerJar,
        Optional.of(tenantId));
    await(
        () -> isActive(baseUrl, "greeter-provider-quota-admission-a"),
        Duration.ofSeconds(60),
        "the first deployment should reach ACTIVE while comfortably within quota");

    HttpResponse<String> rejected =
        submitDeploymentExpectingRejection(
            baseUrl,
            "greeter-provider-quota-admission-b",
            "com.gimle.examples.greeter.provider",
            providerJar,
            tenantId);
    assertEquals(
        409,
        rejected.statusCode(),
        "a second deployment that would push the tenant past its quota must be rejected outright"
            + " at admission, not silently accepted and left for QuotaReconciler to flag later:"
            + " body="
            + rejected.body());

    HttpResponse<String> secondDeploymentStatus =
        httpClient.send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/deployments/greeter-provider-quota-admission-b"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(
        404,
        secondDeploymentStatus.statusCode(),
        "a rejected submission must never be durably created at all, not even in a"
            + " zero-instances/pending state");

    assertTrue(
        isActive(baseUrl, "greeter-provider-quota-admission-a"),
        "the first, already-compliant deployment must be completely unaffected by the second"
            + " submission's rejection");
  }
}
