package com.gimle.hilmir.release;

import com.gimle.core.protocol.Json;
import com.gimle.core.tenant.Tenant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and writes the release ledger: every Hilmir release's state lives as plain {@code
 * ConfigEntry} rows under the fixed bookkeeping tenant {@code gimle-hilmir}, one {@code
 * hilmir.release.<name>.meta} pointer row (see {@link ReleaseMeta}) plus one {@code
 * hilmir.release.<name>.rev.<n>} full-snapshot row (see {@link ReleaseRevision}) per revision ever
 * applied.
 *
 * <p>Named with {@code .} rather than {@code @} as the revision delimiter Fafnir's own {@code
 * key@N}/{@code key@meta} secrets convention uses: the control plane's {@code GET
 * /config/{tenantId}} list endpoint already strips any key whose text after its last {@code @}
 * character reads {@code meta} or is all digits, on the assumption that shape always means a
 * Fafnir-managed secret row written through {@code /secrets/*}. A plain {@code /config/*} ledger
 * key in that exact shape would be silently invisible to {@link #listReleaseNames} and every other
 * read here, which have no way to ask the list endpoint for it back -- there is no per-key {@code
 * GET} on {@code /config/*} to fall back on, only list-and-filter.
 *
 * <p>{@link #readMeta}, {@link #readRevision}, and {@link #listReleaseNames} are public so {@code
 * com.gimle.hilmir.sync} can read a release's current ledger state directly, the same way {@link
 * DeployCommand}/{@link UpgradeCommand} already do -- the write-side methods stay package-private,
 * reached only through {@link ReleaseReconciler}.
 */
public final class ReleaseLedger {

  static final String TENANT = Tenant.HILMIR_BOOKKEEPING_TENANT_ID;
  private static final String KEY_PREFIX = "hilmir.release.";
  private static final String META_SUFFIX = ".meta";
  private static final String REVISION_INFIX = ".rev.";

  private ReleaseLedger() {}

  /**
   * Idempotently ensures the {@value #TENANT} bookkeeping tenant exists. Checks first rather than
   * always issuing the {@code PUT} the control plane's own idempotent tenant write would otherwise
   * make unnecessary: plaintext mode refuses creating a *second* real tenant outright (an
   * intentional restriction -- there's no caller identity to tell co-tenants apart without mTLS),
   * and this bookkeeping tenant is exactly the kind of thing that trips it on a cluster that
   * already has one real tenant of its own, even though nothing about this internal, every-release
   * bootstrap call is the multi-tenant use that rule exists to catch. Skipping the write once
   * {@value #TENANT} is already there -- the common case on every release after the first -- avoids
   * that false trip without ever touching the rule itself for a caller creating a genuine second
   * tenant.
   */
  static void ensureTenant(ControlPlaneApi api) {
    if (api.exists("/tenants/" + TENANT)) {
      return;
    }
    Map<String, Object> quota = new LinkedHashMap<>();
    quota.put("maxMemoryBytes", 0L);
    quota.put("maxCpuMillicores", 0L);
    quota.put("maxInstances", 0);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("quota", quota);
    api.expectSuccess(api.put("/tenants/" + TENANT, Json.write(body)));
  }

  public static List<String> listReleaseNames(ControlPlaneApi api) {
    List<String> names = new ArrayList<>();
    for (Map<String, Object> entry : api.getList("/config/" + TENANT)) {
      String key = (String) entry.get("key");
      if (key != null && key.startsWith(KEY_PREFIX) && key.endsWith(META_SUFFIX)) {
        names.add(key.substring(KEY_PREFIX.length(), key.length() - META_SUFFIX.length()));
      }
    }
    return names;
  }

  public static Optional<ReleaseMeta> readMeta(ControlPlaneApi api, String releaseName) {
    String key = metaKey(releaseName);
    for (Map<String, Object> entry : api.getList("/config/" + TENANT)) {
      if (key.equals(entry.get("key"))) {
        return Optional.of(
            ReleaseMeta.fromJson(Json.asObject(Json.parse((String) entry.get("value")))));
      }
    }
    return Optional.empty();
  }

  static void writeMeta(ControlPlaneApi api, String releaseName, ReleaseMeta meta) {
    putConfig(api, metaKey(releaseName), Json.write(meta.toJson()));
  }

  static void deleteMeta(ControlPlaneApi api, String releaseName) {
    api.expectSuccess(api.delete("/config/" + TENANT + "/" + metaKey(releaseName)));
  }

  public static Optional<ReleaseRevision> readRevision(
      ControlPlaneApi api, String releaseName, int revision) {
    String key = revisionKey(releaseName, revision);
    for (Map<String, Object> entry : api.getList("/config/" + TENANT)) {
      if (key.equals(entry.get("key"))) {
        return Optional.of(
            ReleaseRevision.fromJson(Json.asObject(Json.parse((String) entry.get("value")))));
      }
    }
    return Optional.empty();
  }

  static void writeRevision(ControlPlaneApi api, String releaseName, ReleaseRevision revision) {
    putConfig(api, revisionKey(releaseName, revision.revision()), Json.write(revision.toJson()));
  }

  static void deleteRevision(ControlPlaneApi api, String releaseName, int revision) {
    api.expectSuccess(api.delete("/config/" + TENANT + "/" + revisionKey(releaseName, revision)));
  }

  /** Every revision number currently on the ledger for {@code releaseName}. */
  static List<Integer> listRevisions(ControlPlaneApi api, String releaseName) {
    String prefix = KEY_PREFIX + releaseName + REVISION_INFIX;
    List<Integer> revisions = new ArrayList<>();
    for (Map<String, Object> entry : api.getList("/config/" + TENANT)) {
      String key = (String) entry.get("key");
      if (key != null && key.startsWith(prefix)) {
        revisions.add(Integer.parseInt(key.substring(prefix.length())));
      }
    }
    return revisions;
  }

  private static void putConfig(ControlPlaneApi api, String key, String jsonValue) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("value", jsonValue);
    body.put("encrypted", false);
    api.expectSuccess(api.put("/config/" + TENANT + "/" + key, Json.write(body)));
  }

  private static String metaKey(String releaseName) {
    return KEY_PREFIX + releaseName + META_SUFFIX;
  }

  private static String revisionKey(String releaseName, int revision) {
    return KEY_PREFIX + releaseName + REVISION_INFIX + revision;
  }
}
