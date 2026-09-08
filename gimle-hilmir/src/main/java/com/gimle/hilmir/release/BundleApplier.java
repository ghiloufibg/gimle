package com.gimle.hilmir.release;

import com.gimle.core.protocol.Json;
import com.gimle.hilmir.HilmirException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Applies (and, for {@code upgrade}/{@code rollback}/{@code undeploy}, removes) a rendered bundle's
 * state against a live control plane -- each method is one part of {@code deploy}'s own apply
 * sequence (tenants, then config, then secrets, then workloads), reused as-is by every verb that
 * needs to (re-)apply a bundle.
 *
 * <p>Every {@code apply*} method reports each item back through {@code onApplied} the moment its
 * own {@code PUT} succeeds, before moving on to the next -- each individual {@code PUT} already
 * durably persists that item against the control plane, so a caller catching a later item's failure
 * can still recover exactly what actually landed, rather than losing that information the instant
 * the loop is abandoned partway through (see {@link ReleaseReconciler}'s own use of this).
 */
final class BundleApplier {

  /**
   * How long {@link #deleteWorkloads} keeps polling for a resource to actually disappear before
   * giving up. Configurable for the same reason {@link WaitPoller#TIMEOUT_PROPERTY} is: a
   * DaemonSet's instances can take longer to drain on a larger cluster than a default is willing to
   * assume.
   */
  static final String DELETE_CONFIRM_TIMEOUT_PROPERTY = "gimle.hilmir.deleteConfirmTimeoutMillis";

  private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

  private static Duration deleteConfirmTimeout() {
    return Duration.ofMillis(
        Long.getLong(DELETE_CONFIRM_TIMEOUT_PROPERTY, Duration.ofMinutes(2).toMillis()));
  }

  private BundleApplier() {}

  static void applyTenants(
      ControlPlaneApi api, List<BundleTenant> tenants, Consumer<BundleTenant> onApplied) {
    for (BundleTenant tenant : tenants) {
      Map<String, Object> quota = new LinkedHashMap<>();
      quota.put("maxMemoryBytes", tenant.quota().maxMemoryBytes());
      quota.put("maxCpuMillicores", tenant.quota().maxCpuMillicores());
      quota.put("maxInstances", tenant.quota().maxInstances());
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("quota", quota);
      tenant.isolationPosture().ifPresent(posture -> body.put("isolationPosture", posture));
      api.expectSuccess(api.put("/tenants/" + tenant.id(), Json.write(body)));
      onApplied.accept(tenant);
    }
  }

  static void applyConfig(
      ControlPlaneApi api,
      List<RenderedConfigEntry> config,
      Consumer<RenderedConfigEntry> onApplied) {
    for (RenderedConfigEntry entry : config) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("value", entry.value());
      body.put("encrypted", false);
      api.expectSuccess(api.put("/config/" + entry.tenant() + "/" + entry.key(), Json.write(body)));
      onApplied.accept(entry);
    }
  }

  /**
   * Secret values cross the wire base64-encoded, matching {@code gimle-cli}'s own {@code
   * /secrets/*} convention.
   */
  static void applySecrets(
      ControlPlaneApi api,
      List<RenderedSecretEntry> secrets,
      Consumer<RenderedSecretEntry> onApplied) {
    for (RenderedSecretEntry entry : secrets) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put(
          "value",
          Base64.getEncoder().encodeToString(entry.value().getBytes(StandardCharsets.UTF_8)));
      api.expectSuccess(
          api.put("/secrets/" + entry.tenant() + "/" + entry.key(), Json.write(body)));
      onApplied.accept(entry);
    }
  }

  static void applyWorkloads(
      ControlPlaneApi api, List<RenderedWorkload> workloads, Consumer<RenderedWorkload> onApplied) {
    for (RenderedWorkload workload : workloads) {
      String prefix = WorkloadKinds.pathPrefix(workload.kind());
      api.expectSuccess(api.put(prefix + workload.name(), workload.yaml()));
      onApplied.accept(workload);
    }
  }

  /**
   * Deletes {@code resources} in the order given -- callers pass reverse-apply order for undeploy
   * -- and confirms each one is actually gone before moving on to the next. A 2xx {@code DELETE}
   * response only proves the control plane accepted the request, not that the resource finished
   * terminating: a DaemonSet whose instances are slow, stuck, or unhealthy can still round-trip on
   * a {@code GET} immediately afterward. Callers such as {@link ReleaseReconciler#undeployRelease}
   * treat "every workload deleted" as license to drop the release's own ledger bookkeeping next --
   * doing that while a workload might still be live would leave the ledger empty for a release
   * that, in reality, is still there.
   */
  static void deleteWorkloads(ControlPlaneApi api, List<ResourceRef> resources) {
    for (ResourceRef resource : resources) {
      String path = WorkloadKinds.pathPrefix(resource.kind()) + resource.name();
      api.expectSuccess(api.delete(path));
      awaitGone(api, resource, path);
    }
  }

  private static void awaitGone(ControlPlaneApi api, ResourceRef resource, String path) {
    Duration timeout = deleteConfirmTimeout();
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    while (api.exists(path)) {
      if (System.nanoTime() > deadlineNanos) {
        throw new HilmirException(
            "timed out after "
                + timeout
                + " waiting for "
                + resource.kind()
                + " "
                + resource.name()
                + " to finish terminating -- it was still present after its delete was accepted");
      }
      sleep();
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(POLL_INTERVAL);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HilmirException("interrupted while waiting for a deleted workload to disappear", e);
    }
  }

  /**
   * Deletes config keys a release no longer declares. {@code expectSuccess} is deliberately not
   * used: an entry an operator already removed by hand answers 404, which is the end state this
   * call is asking for, not a reason to fail the upgrade.
   */
  static void deleteConfig(ControlPlaneApi api, List<KeyRef> keys) {
    for (KeyRef key : keys) {
      api.delete("/config/" + key.tenant() + "/" + key.key());
    }
  }

  /** Deletes vault keys a release no longer declares -- see {@link #deleteConfig} on 404s. */
  static void deleteSecrets(ControlPlaneApi api, List<KeyRef> keys) {
    for (KeyRef key : keys) {
      api.delete("/secrets/" + key.tenant() + "/" + key.key());
    }
  }

  static void deleteTenants(ControlPlaneApi api, List<String> tenantIds) {
    for (String tenantId : tenantIds) {
      api.expectSuccess(api.delete("/tenants/" + tenantId));
    }
  }
}
