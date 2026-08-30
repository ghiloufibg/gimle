package com.gimle.controlplane.config;

import com.gimle.core.config.ConfigEntry;
import com.gimle.core.protocol.Json;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.store.LeaseGrant;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Version history for plain, unencrypted {@code /config/*} entries -- ported from {@code
 * com.gimle.fafnir.secretmap.SecretMapStore}'s own group-version ledger, adapted from a multi-key
 * group to a single flat value. The live row at {@code ConfigEntry(tenantId, key)} is left
 * completely untouched in shape -- still exactly one plaintext value at exactly the key a caller
 * wrote, exactly as {@code com.gimle.controlplane.admission.PolicyConfigPlugin} and every other
 * direct reader of {@code StoreClient#listConfigEntriesFor} already expect it to be. Every
 * successful mutation additionally stamps an immutable snapshot into a version ledger filed under a
 * synthetic tenant id no real tenant can ever be (see {@link #metaTenantId}) -- the identical
 * reason {@code SecretMapStore} files its own group-version rows there rather than under a
 * same-tenant key-prefix convention: a prefix only hides a row from a caller that applies the right
 * filter, it can't stop a caller from deleting it by exact key, while a synthetic tenant is
 * genuinely unaddressable through {@code /config/{tenantId}/*} for the real {@code tenantId}.
 *
 * <p>Encrypted {@code /config/*} writes ({@code encrypted=true}) never reach this class -- {@code
 * ApiServer} routes those straight to Fafnir's encrypt call and a plain {@link
 * StateMutation.PutConfigEntry} proposal exactly as it did before this class existed. Versioning
 * here only ever covers the plaintext path; introducing crypto-aware history would need Fafnir's
 * own key-rotation-aware versioning, not this store.
 *
 * <p>Version numbers are always minted from this ledger (see {@link #nextVersion}), never inferred
 * from whether the live row currently exists -- a key that is deleted and later recreated keeps
 * counting up from where it left off rather than restarting at 1, which is what keeps a later
 * version number from ever colliding with an earlier ledger entry the way restarting would.
 */
public final class ConfigVersionStore {

  // Same bound ConfigMapStore/SecretMapStore use for their own write-lease retry loops -- generous
  // on purpose, since a real deployment sees at most a couple of colliding writers to one key, not
  // the fully-simultaneous N-way race a stress test deliberately creates.
  private static final int MAX_WRITE_ATTEMPTS = 50;
  private static final Duration WRITE_LEASE_TTL = Duration.ofSeconds(10);
  private static final String VERSION_KEY_PREFIX = "config-version:";
  // No real tenant id can ever equal this -- see SecretMapStore's own identical convention for why
  // group/version ledger rows are filed under a synthetic tenant id rather than the real one.
  private static final String META_TENANT_PREFIX = "gimle-internal:config-meta:";

  private final StoreClient storeClient;

  public ConfigVersionStore(StoreClient storeClient) {
    this.storeClient = storeClient;
  }

  /** Overwrites the live row and stamps the new content as the next ledger version. */
  public ConfigWriteOutcome put(String tenantId, String key, String value) {
    String leaseName = leaseName(tenantId, key);
    String holderId = UUID.randomUUID().toString();
    for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt++) {
      LeaseGrant lease = storeClient.tryAcquireOrRenewLease(leaseName, holderId, WRITE_LEASE_TTL);
      if (!lease.granted()) {
        continue; // another writer is mid-operation for this exact key; retry once it frees up
      }
      try {
        storeClient.propose(
            new StateMutation.PutConfigEntry(
                new ConfigEntry(tenantId, key, value.getBytes(StandardCharsets.UTF_8), false)));
        int version = nextVersion(tenantId, key);
        stampVersion(tenantId, key, version, value, false);
        return new ConfigWriteOutcome.Written(version);
      } finally {
        storeClient.releaseLease(leaseName, holderId);
      }
    }
    return new ConfigWriteOutcome.WriteContention(MAX_WRITE_ATTEMPTS);
  }

  /**
   * Removes the live row and stamps a deleted tombstone version -- a no-op (no version minted) if
   * the key doesn't currently exist, matching every other resource kind's own
   * delete-of-a-never-existed-name convention.
   */
  public ConfigDeleteOutcome delete(String tenantId, String key) {
    String leaseName = leaseName(tenantId, key);
    String holderId = UUID.randomUUID().toString();
    for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt++) {
      LeaseGrant lease = storeClient.tryAcquireOrRenewLease(leaseName, holderId, WRITE_LEASE_TTL);
      if (!lease.granted()) {
        continue;
      }
      try {
        if (findLiveLinearizable(tenantId, key).isEmpty()) {
          return new ConfigDeleteOutcome.NotFound();
        }
        storeClient.propose(new StateMutation.RemoveConfigEntry(tenantId, key));
        int version = nextVersion(tenantId, key);
        stampVersion(tenantId, key, version, null, true);
        return new ConfigDeleteOutcome.Deleted(version);
      } finally {
        storeClient.releaseLease(leaseName, holderId);
      }
    }
    return new ConfigDeleteOutcome.WriteContention(MAX_WRITE_ATTEMPTS);
  }

  /** Every version ever stamped for {@code key}, oldest first. */
  public List<ConfigVersion> listVersions(String tenantId, String key) {
    List<ConfigVersion> result = new ArrayList<>();
    for (ConfigEntry entry : storeClient.listConfigEntriesForLinearizable(metaTenantId(tenantId))) {
      if (isVersionKey(entry.key(), key)) {
        result.add(decodeVersion(entry.value()));
      }
    }
    result.sort(Comparator.comparingInt(ConfigVersion::version));
    return result;
  }

  /**
   * Restores {@code targetVersion}'s content (or deleted state) as a brand-new ledger version --
   * never rewrites {@code targetVersion} or anything stamped after it, the same "restore = re-apply
   * as a new revision" semantics {@code SecretMapStore#rollback} documents.
   */
  public ConfigRollbackOutcome rollback(String tenantId, String key, int targetVersion) {
    String leaseName = leaseName(tenantId, key);
    String holderId = UUID.randomUUID().toString();
    for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt++) {
      LeaseGrant lease = storeClient.tryAcquireOrRenewLease(leaseName, holderId, WRITE_LEASE_TTL);
      if (!lease.granted()) {
        continue;
      }
      try {
        Optional<ConfigVersion> target = findVersion(tenantId, key, targetVersion);
        if (target.isEmpty()) {
          return new ConfigRollbackOutcome.TargetNotFound();
        }
        int version = nextVersion(tenantId, key);
        if (target.get().deleted()) {
          storeClient.propose(new StateMutation.RemoveConfigEntry(tenantId, key));
          stampVersion(tenantId, key, version, null, true);
          return new ConfigRollbackOutcome.Applied(version, Optional.empty(), true);
        }
        String value = target.get().value();
        storeClient.propose(
            new StateMutation.PutConfigEntry(
                new ConfigEntry(tenantId, key, value.getBytes(StandardCharsets.UTF_8), false)));
        stampVersion(tenantId, key, version, value, false);
        return new ConfigRollbackOutcome.Applied(version, Optional.of(value), false);
      } finally {
        storeClient.releaseLease(leaseName, holderId);
      }
    }
    return new ConfigRollbackOutcome.WriteContention(MAX_WRITE_ATTEMPTS);
  }

  private Optional<ConfigEntry> findLiveLinearizable(String tenantId, String key) {
    return storeClient.listConfigEntriesForLinearizable(tenantId).stream()
        .filter(e -> e.key().equals(key))
        .findFirst();
  }

  private Optional<ConfigVersion> findVersion(String tenantId, String key, int version) {
    String rawKey = versionKey(key, version);
    for (ConfigEntry entry : storeClient.listConfigEntriesForLinearizable(metaTenantId(tenantId))) {
      if (entry.key().equals(rawKey)) {
        return Optional.of(decodeVersion(entry.value()));
      }
    }
    return Optional.empty();
  }

  private int nextVersion(String tenantId, String key) {
    int max = 0;
    for (ConfigEntry entry : storeClient.listConfigEntriesForLinearizable(metaTenantId(tenantId))) {
      if (isVersionKey(entry.key(), key)) {
        max = Math.max(max, decodeVersion(entry.value()).version());
      }
    }
    return max + 1;
  }

  private void stampVersion(
      String tenantId, String key, int version, String value, boolean deleted) {
    ConfigVersion snapshot = new ConfigVersion(version, value, deleted);
    storeClient.propose(
        new StateMutation.PutConfigEntry(
            new ConfigEntry(
                metaTenantId(tenantId), versionKey(key, version), encodeVersion(snapshot), false)));
  }

  private static String leaseName(String tenantId, String key) {
    return "config-write:" + tenantId + ":" + key;
  }

  // The synthetic tenant id ledger rows are filed under -- see the class javadoc for why.
  private static String metaTenantId(String tenantId) {
    return META_TENANT_PREFIX + tenantId;
  }

  private static String versionKey(String key, int version) {
    return VERSION_KEY_PREFIX + key + ":" + version;
  }

  // "config-version:{key}:" is this convention's own separator between the key and the version
  // number -- checking the prefix this way sidesteps ever having to worry about a key containing
  // what would otherwise look like a boundary.
  private static boolean isVersionKey(String rawKey, String key) {
    return rawKey.startsWith(VERSION_KEY_PREFIX + key + ":");
  }

  private static byte[] encodeVersion(ConfigVersion snapshot) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("version", snapshot.version());
    map.put("value", snapshot.value());
    map.put("deleted", snapshot.deleted());
    return Json.write(map).getBytes(StandardCharsets.UTF_8);
  }

  private static ConfigVersion decodeVersion(byte[] bytes) {
    Map<String, Object> map = Json.asObject(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
    int version = ((Number) map.get("version")).intValue();
    Object rawValue = map.get("value");
    String value = rawValue == null ? null : String.valueOf(rawValue);
    boolean deleted = Boolean.TRUE.equals(map.get("deleted"));
    return new ConfigVersion(version, value, deleted);
  }
}
