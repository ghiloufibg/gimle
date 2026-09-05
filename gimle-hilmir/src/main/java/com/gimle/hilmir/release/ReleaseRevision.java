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
 * <p>Secrets appear here as a {@link SecretRef} -- tenant, key and a digest of the value, never the
 * value -- because this row is written through the plain, unencrypted {@code /config/*} surface
 * (see {@link ReleaseLedger}). See {@code SecretRef} for why the digest is enough.
 *
 * <p>Public so {@code com.gimle.hilmir.sync} can read a release's last-applied content back through
 * {@link ReleaseLedger#readRevision} and compare it against a freshly rendered candidate via {@link
 * #matchesContent}.
 */
public record ReleaseRevision(
    int revision,
    long appliedAtEpochMilli,
    List<BundleTenant> tenants,
    List<RenderedConfigEntry> config,
    List<SecretRef> secrets,
    List<RenderedWorkload> workloads,
    Optional<Integer> rollbackOfRevision) {

  List<ResourceRef> resources() {
    return workloads.stream().map(w -> new ResourceRef(w.kind(), w.name())).toList();
  }

  /**
   * Whether this revision's own content already matches {@code rendered} exactly -- plain record
   * {@code equals()} on each of the four content fields, deliberately ignoring this revision's own
   * ledger-only bookkeeping fields ({@code revision}, {@code appliedAtEpochMilli}, {@code
   * rollbackOfRevision}), which have no counterpart on a freshly rendered bundle. Comparison is
   * list-order-sensitive: a bundle re-declaring the exact same tenants/config/secrets/workloads in
   * a different order reads as changed, not converged -- a deliberate v1 simplification, since a
   * genuinely unchanged bundle file renders its lists in the same order every time.
   */
  public boolean matchesContent(RenderedBundle rendered) {
    return tenants.equals(rendered.tenants())
        && config.equals(rendered.config())
        && secrets.equals(rendered.secrets().stream().map(SecretRef::of).toList())
        && workloads.equals(rendered.workloads());
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
    List<SecretRef> secrets = new ArrayList<>();
    for (Map<String, Object> s : Json.asObjectList(json.get("secrets"))) {
      secrets.add(
          new SecretRef(
              (String) s.get("tenant"), (String) s.get("key"), (String) s.get("valueDigest")));
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
    tenant.isolationPosture().ifPresent(posture -> json.put("isolationPosture", posture));
    return json;
  }

  private static BundleTenant tenantFromJson(Map<String, Object> json) {
    Map<String, Object> quota = Json.asObject(json.get("quota"));
    return new BundleTenant(
        (String) json.get("id"),
        new BundleQuota(
            ((Number) quota.get("maxMemoryBytes")).longValue(),
            ((Number) quota.get("maxCpuMillicores")).longValue(),
            ((Number) quota.get("maxInstances")).intValue()),
        Optional.ofNullable((String) json.get("isolationPosture")));
  }

  private static Map<String, Object> configToJson(RenderedConfigEntry entry) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("tenant", entry.tenant());
    json.put("key", entry.key());
    json.put("value", entry.value());
    return json;
  }

  private static Map<String, Object> secretToJson(SecretRef entry) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("tenant", entry.tenant());
    json.put("key", entry.key());
    json.put("valueDigest", entry.valueDigest());
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
