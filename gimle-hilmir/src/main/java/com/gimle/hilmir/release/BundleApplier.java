package com.gimle.hilmir.release;

import com.gimle.core.protocol.Json;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies (and, for {@code upgrade}/{@code rollback}/{@code undeploy}, removes) a rendered bundle's
 * state against a live control plane -- each method is one part of {@code deploy}'s own apply
 * sequence (tenants, then config, then secrets, then workloads), reused as-is by every verb that
 * needs to (re-)apply a bundle.
 */
final class BundleApplier {

  private BundleApplier() {}

  static void applyTenants(ControlPlaneApi api, List<BundleTenant> tenants) {
    for (BundleTenant tenant : tenants) {
      Map<String, Object> quota = new LinkedHashMap<>();
      quota.put("maxMemoryBytes", tenant.quota().maxMemoryBytes());
      quota.put("maxCpuMillicores", tenant.quota().maxCpuMillicores());
      quota.put("maxInstances", tenant.quota().maxInstances());
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("quota", quota);
      tenant.isolationPosture().ifPresent(posture -> body.put("isolationPosture", posture));
      api.expectSuccess(api.put("/tenants/" + tenant.id(), Json.write(body)));
    }
  }

  static void applyConfig(ControlPlaneApi api, List<RenderedConfigEntry> config) {
    for (RenderedConfigEntry entry : config) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("value", entry.value());
      body.put("encrypted", false);
      api.expectSuccess(api.put("/config/" + entry.tenant() + "/" + entry.key(), Json.write(body)));
    }
  }

  /**
   * Secret values cross the wire base64-encoded, matching {@code gimle-cli}'s own {@code
   * /secrets/*} convention.
   */
  static void applySecrets(ControlPlaneApi api, List<RenderedSecretEntry> secrets) {
    for (RenderedSecretEntry entry : secrets) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put(
          "value",
          Base64.getEncoder().encodeToString(entry.value().getBytes(StandardCharsets.UTF_8)));
      api.expectSuccess(
          api.put("/secrets/" + entry.tenant() + "/" + entry.key(), Json.write(body)));
    }
  }

  static void applyWorkloads(ControlPlaneApi api, List<RenderedWorkload> workloads) {
    for (RenderedWorkload workload : workloads) {
      String prefix = WorkloadKinds.pathPrefix(workload.kind());
      api.expectSuccess(api.put(prefix + workload.name(), workload.yaml()));
    }
  }

  /**
   * Deletes {@code resources} in the order given -- callers pass reverse-apply order for undeploy.
   */
  static void deleteWorkloads(ControlPlaneApi api, List<ResourceRef> resources) {
    for (ResourceRef resource : resources) {
      api.expectSuccess(api.delete(WorkloadKinds.pathPrefix(resource.kind()) + resource.name()));
    }
  }

  static void deleteTenants(ControlPlaneApi api, List<String> tenantIds) {
    for (String tenantId : tenantIds) {
      api.expectSuccess(api.delete("/tenants/" + tenantId));
    }
  }
}
