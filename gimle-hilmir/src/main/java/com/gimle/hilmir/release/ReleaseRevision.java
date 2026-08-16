package com.gimle.hilmir.release;

import com.gimle.core.protocol.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code hilmir.release.<name>.rev.<n>} full-snapshot row: every rendered manifest actually
 * applied for this revision, plus enough of the source bundle (tenants, plain config, secrets) for
 * {@code rollback} to re-apply this exact revision again later without needing the original bundle
 * file to still exist on disk. {@code rollbackOfRevision} is set only when this revision was itself
 * produced by {@code rollback}, recording which earlier revision it restored -- rollback always
 * writes a new revision, never rewrites history in place.
 *
 * <p>Secret values are stored here rendered (plaintext), the same way every other field in this
 * snapshot is: this row is written through the plain, unencrypted {@code /config/*} surface (see
 * {@link ReleaseLedger}), not Fafnir's own encrypted {@code /secrets/*} one, so a release's secret
 * material is only ever as protected as the rest of its ledger history. A deliberate v1 tradeoff --
 * re-applying a rollback byte-for-byte requires the exact value that was set, and the {@code
 * /secrets/*} surface has no "read back the plaintext I previously wrote" endpoint to recover it
 * from instead.
 */
record ReleaseRevision(
    int revision,
    long appliedAtEpochMilli,
    List<BundleTenant> tenants,
    List<RenderedConfigEntry> config,
    List<RenderedSecretEntry> secrets,
    List<RenderedWorkload> workloads,
    Optional<Integer> rollbackOfRevision) {

  List<ResourceRef> resources() {
    return workloads.stream().map(w -> new ResourceRef(w.kind(), w.name())).toList();
  }

  Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("revision", revision);
    json.put("appliedAtEpochMilli", appliedAtEpochMilli);
    json.put("resources", resources().stream().map(ResourceRef::toJson).toList());
    json.put("tenants", tenants.stream().map(ReleaseRevision::tenantToJson).toList());
    json.put("config", config.stream().map(ReleaseRevision::configToJson).toList());
    json.put("secrets", secrets.stream().map(ReleaseRevision::secretToJson).toList());
    json.put("workloads", workloads.stream().map(ReleaseRevision::workloadToJson).toList());
    rollbackOfRevision.ifPresent(r -> json.put("rollbackOfRevision", r));
    return json;
  }

  static ReleaseRevision fromJson(Map<String, Object> json) {
    List<BundleTenant> tenants = new ArrayList<>();
    for (Map<String, Object> t : Json.asObjectList(json.get("tenants"))) {
      tenants.add(tenantFromJson(t));
    }
    List<RenderedConfigEntry> config = new ArrayList<>();
    for (Map<String, Object> c : Json.asObjectList(json.get("config"))) {
      config.add(
          new RenderedConfigEntry(
              (String) c.get("tenant"), (String) c.get("key"), (String) c.get("value")));
    }
    List<RenderedSecretEntry> secrets = new ArrayList<>();
    for (Map<String, Object> s : Json.asObjectList(json.get("secrets"))) {
      secrets.add(
          new RenderedSecretEntry(
              (String) s.get("tenant"), (String) s.get("key"), (String) s.get("value")));
    }
    List<RenderedWorkload> workloads = new ArrayList<>();
    for (Map<String, Object> w : Json.asObjectList(json.get("workloads"))) {
      workloads.add(
          new RenderedWorkload(
              (String) w.get("kind"), (String) w.get("name"), (String) w.get("yaml")));
    }
    Object rollbackOf = json.get("rollbackOfRevision");
    return new ReleaseRevision(
        ((Number) json.get("revision")).intValue(),
        ((Number) json.get("appliedAtEpochMilli")).longValue(),
        tenants,
        config,
        secrets,
        workloads,
        rollbackOf == null ? Optional.empty() : Optional.of(((Number) rollbackOf).intValue()));
  }

  private static Map<String, Object> tenantToJson(BundleTenant tenant) {
    Map<String, Object> quota = new LinkedHashMap<>();
    quota.put("maxMemoryBytes", tenant.quota().maxMemoryBytes());
    quota.put("maxCpuMillicores", tenant.quota().maxCpuMillicores());
    quota.put("maxInstances", tenant.quota().maxInstances());
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", tenant.id());
    json.put("quota", quota);
    return json;
  }

  private static BundleTenant tenantFromJson(Map<String, Object> json) {
    Map<String, Object> quota = Json.asObject(json.get("quota"));
    return new BundleTenant(
        (String) json.get("id"),
        new BundleQuota(
            ((Number) quota.get("maxMemoryBytes")).longValue(),
            ((Number) quota.get("maxCpuMillicores")).longValue(),
            ((Number) quota.get("maxInstances")).intValue()));
  }

  private static Map<String, Object> configToJson(RenderedConfigEntry entry) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("tenant", entry.tenant());
    json.put("key", entry.key());
    json.put("value", entry.value());
    return json;
  }

  private static Map<String, Object> secretToJson(RenderedSecretEntry entry) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("tenant", entry.tenant());
    json.put("key", entry.key());
    json.put("value", entry.value());
    return json;
  }

  private static Map<String, Object> workloadToJson(RenderedWorkload workload) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("kind", workload.kind());
    json.put("name", workload.name());
    json.put("yaml", workload.yaml());
    return json;
  }
}
