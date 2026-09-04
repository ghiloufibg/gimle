package com.gimle.controlplane.configmap;

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
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;

/**
 * ConfigMap CRUD, backed by the same {@link com.gimle.core.config.ConfigEntry} store {@code
 * gimle-fafnir}'s {@code SecretStore} writes to -- see {@link ConfigMapCodec} for the storage
 * convention. Constructed with a full {@link StoreClient} (not just a {@code StoreReader}), since
 * {@link #put}/{@link #patch} need the lease and linearizable-read primitives only {@code
 * StoreClient} exposes.
 *
 * <p>Every successful mutation (put/patch/delete/rollback) also mints and stamps an immutable
 * version-ledger snapshot -- ported from {@code com.gimle.fafnir.secretmap.SecretMapStore}'s own
 * group-version ledger, filed under a synthetic tenant id (see {@link #metaTenantId}) for the
 * identical reason that class gives: a same-tenant key-prefix convention only hides a row from a
 * caller applying the right filter, it can't stop a caller from deleting it by exact key, while a
 * synthetic tenant is genuinely unaddressable through {@code /configmaps/{tenantId}/*} for the real
 * {@code tenantId}. Ledger version numbers are always minted from this ledger (see {@link
 * #nextVersion}), never inferred from whether the live row currently exists, so a name that is
 * deleted and later recreated keeps counting up from where it left off rather than restarting at 1
 * -- which is what keeps a later version number from ever colliding with an earlier ledger entry
 * the way restarting would. The live row's own {@code version} field stays exactly what it always
 * was -- the optimistic-concurrency token {@link #patch}'s {@code expectedVersion} check compares
 * against -- and happens to always equal the most recently minted ledger version, since every
 * mutation stamps both together under the same write lease.
 */
public final class ConfigMapStore {

  // Same bound SecretStore.put uses for its own optimistic write-verify-retry loop -- generous on
  // purpose, since a real deployment sees at most a couple of colliding writers, not the
  // fully-simultaneous N-way race a stress test deliberately creates.
  private static final int MAX_WRITE_ATTEMPTS = 50;
  // Sized for a full read-merge-write payload, not just a pointer advance like SecretStore's own
  // meta-lease TTL -- a ConfigMap write carries whatever data map the caller sent, not one
  // pre-encrypted value.
  private static final Duration WRITE_LEASE_TTL = Duration.ofSeconds(10);
  private static final String VERSION_KEY_PREFIX = "configmap-version:";
  // No real tenant id can ever equal this -- see SecretMapStore's own identical convention for why
  // ledger rows are filed under a synthetic tenant id rather than the real one.
  private static final String META_TENANT_PREFIX = "gimle-internal:configmap-meta:";

  private final StoreClient storeClient;

  public ConfigMapStore(StoreClient storeClient) {
    this.storeClient = storeClient;
  }

  public List<String> list(String tenantId) {
    return ConfigMapCodec.findAll(storeClient, tenantId).stream().map(ConfigMap::name).toList();
  }

  public Optional<ConfigMap> get(String tenantId, String name) {
    return ConfigMapCodec.find(storeClient, tenantId, name);
  }

  /**
   * One store round trip, filtered client-side to just the requested {@code names} -- the batch
   * shape {@code gimle-agent} uses to fetch every {@code configMapRefs} entry in one call rather
   * than one HTTP round trip per referenced ConfigMap.
   */
  public List<ConfigMap> getMany(String tenantId, List<String> names) {
    return ConfigMapCodec.findAll(storeClient, tenantId).stream()
        .filter(cm -> names.contains(cm.name()))
        .toList();
  }

  /** Full replace: {@code expectedVersion} absent means unconditional overwrite. */
  public ConfigMapWriteResult put(
      String tenantId, String name, Map<String, String> data, OptionalInt expectedVersion) {
    return write(tenantId, name, expectedVersion, before -> data);
  }

  /**
   * Partial merge: only the key(s) in {@code data} are touched, every other existing key survives
   * untouched. {@code expectedVersion} is required by the API layer (a missing value is a 400, not
   * "unconditional"); the value {@code 0} is the create case -- naturally satisfied here since a
   * never-written ConfigMap's current version defaults to {@code 0}, requiring no special-casing.
   */
  public ConfigMapWriteResult patch(
      String tenantId, String name, Map<String, String> data, int expectedVersion) {
    return write(
        tenantId,
        name,
        OptionalInt.of(expectedVersion),
        before -> {
          Map<String, String> merged =
              new LinkedHashMap<>(before.map(ConfigMap::data).orElse(Map.of()));
          merged.putAll(data);
          return merged;
        });
  }

  /**
   * Idempotent no-op (mints no ledger version) if {@code name} doesn't currently exist -- matching
   * every other resource kind's own delete-of-a-never-existed-name convention. Lease-guarded the
   * same as {@link #write}, so a delete racing a concurrent put/patch/rollback on the same name is
   * serialized rather than interleaved.
   */
  public ConfigMapDeleteOutcome delete(String tenantId, String name) {
    String leaseName = leaseName(tenantId, name);
    String holderId = UUID.randomUUID().toString();
    for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt++) {
      LeaseGrant lease = storeClient.tryAcquireOrRenewLease(leaseName, holderId, WRITE_LEASE_TTL);
      if (!lease.granted()) {
        continue;
      }
      try {
        if (findLinearizable(tenantId, name).isEmpty()) {
          return new ConfigMapDeleteOutcome.NotFound();
        }
        storeClient.propose(
            new StateMutation.RemoveConfigEntry(tenantId, ConfigMapCodec.keyFor(name)));
        int version = nextVersion(tenantId, name);
        stampVersion(tenantId, name, version, Map.of(), true);
        return new ConfigMapDeleteOutcome.Deleted(version);
      } finally {
        storeClient.releaseLease(leaseName, holderId);
      }
    }
    return new ConfigMapDeleteOutcome.WriteContention(MAX_WRITE_ATTEMPTS);
  }

  /** Every version ever stamped for {@code name}, oldest first. */
  public List<ConfigMapVersion> listVersions(String tenantId, String name) {
    List<ConfigMapVersion> result = new ArrayList<>();
    for (ConfigEntry entry : storeClient.listConfigEntriesFor(metaTenantId(tenantId))) {
      if (isVersionKey(entry.key(), name)) {
        result.add(decodeVersion(entry.value()));
      }
    }
    result.sort(Comparator.comparingInt(ConfigMapVersion::version));
    return result;
  }

  /**
   * Restores {@code targetVersion}'s content (or deleted state) as a brand-new ledger version --
   * never rewrites {@code targetVersion} or anything stamped after it, the same "restore = re-apply
   * as a new revision" semantics {@code SecretMapStore#rollback} documents.
   */
  public ConfigMapRollbackOutcome rollback(String tenantId, String name, int targetVersion) {
    String leaseName = leaseName(tenantId, name);
    String holderId = UUID.randomUUID().toString();
    for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt++) {
      LeaseGrant lease = storeClient.tryAcquireOrRenewLease(leaseName, holderId, WRITE_LEASE_TTL);
      if (!lease.granted()) {
        continue;
      }
      try {
        Optional<ConfigMapVersion> target = findVersion(tenantId, name, targetVersion);
        if (target.isEmpty()) {
          return new ConfigMapRollbackOutcome.TargetNotFound();
        }
        int version = nextVersion(tenantId, name);
        if (target.get().deleted()) {
          storeClient.propose(
              new StateMutation.RemoveConfigEntry(tenantId, ConfigMapCodec.keyFor(name)));
          stampVersion(tenantId, name, version, Map.of(), true);
          return new ConfigMapRollbackOutcome.Applied(version, Map.of(), true);
        }
        Map<String, String> data = target.get().data();
        storeClient.propose(
            new StateMutation.PutConfigEntry(
                ConfigMapCodec.encode(new ConfigMap(tenantId, name, version, data))));
        stampVersion(tenantId, name, version, data, false);
        return new ConfigMapRollbackOutcome.Applied(version, data, false);
      } finally {
        storeClient.releaseLease(leaseName, holderId);
      }
    }
    return new ConfigMapRollbackOutcome.WriteContention(MAX_WRITE_ATTEMPTS);
  }

  /**
   * The write path shared by {@link #put} and {@link #patch}: one lease, acquired first and held
   * across the entire read-check-propose-stamp sequence, not just a narrow final verify-and-advance
   * step the way {@code SecretStore.put}'s own lease is scoped. That narrower shape exists there to
   * protect a two-phase "claim an immutable version, then advance a separate mutable pointer"
   * write; a ConfigMap's live row and its ledger stamp are minted together under one lease here, so
   * there is no second phase to leave outside it -- covering the whole critical section with a
   * single lease is both simpler and exactly as safe.
   */
  private ConfigMapWriteResult write(
      String tenantId,
      String name,
      OptionalInt expectedVersion,
      Function<Optional<ConfigMap>, Map<String, String>> computeNewData) {
    String leaseName = leaseName(tenantId, name);
    // Fresh per call, not a shared field: two concurrent writers -- exactly the case this lease
    // exists to serialize -- must present distinct holder identities, or the store's own
    // "already held by this holderId" renewal rule would let both hold the lease at once.
    String holderId = UUID.randomUUID().toString();
    for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt++) {
      LeaseGrant lease = storeClient.tryAcquireOrRenewLease(leaseName, holderId, WRITE_LEASE_TTL);
      if (!lease.granted()) {
        // Another writer is mid-write for this exact ConfigMap; retry once it frees up.
        continue;
      }
      try {
        Optional<ConfigMap> before = findLinearizable(tenantId, name);
        int currentVersion = before.map(ConfigMap::version).orElse(0);
        if (expectedVersion.isPresent() && expectedVersion.getAsInt() != currentVersion) {
          // Immediate, no retry: a stale expectedVersion is the caller's problem to resolve, not
          // this store's -- see this class's own javadoc reference to the design's failure table.
          return new ConfigMapWriteResult.VersionConflict(
              currentVersion, before.map(ConfigMap::data).orElse(Map.of()));
        }
        Map<String, String> newData = computeNewData.apply(before);
        // Minted from the ledger, not currentVersion + 1: keeps numbering monotonic across a
        // delete-then-recreate cycle -- see this class's own javadoc for why that matters.
        int newVersion = nextVersion(tenantId, name);
        storeClient.propose(
            new StateMutation.PutConfigEntry(
                ConfigMapCodec.encode(new ConfigMap(tenantId, name, newVersion, newData))));
        stampVersion(tenantId, name, newVersion, newData, false);
        return new ConfigMapWriteResult.Written(newVersion);
      } finally {
        storeClient.releaseLease(leaseName, holderId);
      }
    }
    return new ConfigMapWriteResult.WriteContention(MAX_WRITE_ATTEMPTS);
  }

  private Optional<ConfigMapVersion> findVersion(String tenantId, String name, int version) {
    String rawKey = versionKey(name, version);
    for (ConfigEntry entry : storeClient.listConfigEntriesFor(metaTenantId(tenantId))) {
      if (entry.key().equals(rawKey)) {
        return Optional.of(decodeVersion(entry.value()));
      }
    }
    return Optional.empty();
  }

  private int nextVersion(String tenantId, String name) {
    int max = 0;
    for (ConfigEntry entry : storeClient.listConfigEntriesFor(metaTenantId(tenantId))) {
      if (isVersionKey(entry.key(), name)) {
        max = Math.max(max, decodeVersion(entry.value()).version());
      }
    }
    return max + 1;
  }

  private void stampVersion(
      String tenantId, String name, int version, Map<String, String> data, boolean deleted) {
    ConfigMapVersion snapshot = new ConfigMapVersion(version, data, deleted);
    storeClient.propose(
        new StateMutation.PutConfigEntry(
            new ConfigEntry(
                metaTenantId(tenantId),
                versionKey(name, version),
                encodeVersion(snapshot),
                false)));
  }

  private static String leaseName(String tenantId, String name) {
    return "configmap-write:" + tenantId + ":" + name;
  }

  // The synthetic tenant id ledger rows are filed under -- see the class javadoc for why.
  private static String metaTenantId(String tenantId) {
    return META_TENANT_PREFIX + tenantId;
  }

  private static String versionKey(String name, int version) {
    return VERSION_KEY_PREFIX + name + ":" + version;
  }

  // "configmap-version:{name}:" is this convention's own separator between the name and the
  // version number -- checking the prefix this way sidesteps ever having to worry about a name
  // containing what would otherwise look like a boundary.
  private static boolean isVersionKey(String rawKey, String name) {
    return rawKey.startsWith(VERSION_KEY_PREFIX + name + ":");
  }

  private static byte[] encodeVersion(ConfigMapVersion snapshot) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("version", snapshot.version());
    map.put("data", snapshot.data());
    map.put("deleted", snapshot.deleted());
    return Json.write(map).getBytes(StandardCharsets.UTF_8);
  }

  private static ConfigMapVersion decodeVersion(byte[] bytes) {
    Map<String, Object> map = Json.asObject(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
    int version = ((Number) map.get("version")).intValue();
    Map<String, Object> rawData = Json.asObject(map.get("data"));
    Map<String, String> data = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : rawData.entrySet()) {
      data.put(e.getKey(), String.valueOf(e.getValue()));
    }
    boolean deleted = Boolean.TRUE.equals(map.get("deleted"));
    return new ConfigMapVersion(version, data, deleted);
  }

  /**
   * Leader-routed, linearizable read for the write path's own "before" snapshot -- a plain
   * round-robin read could land on a lagging replica and see a stale version, making the {@code
   * expectedVersion} check pass when it shouldn't. Ordinary {@link #get}/{@link #list}/{@link
   * #getMany} reads stay plain round-robin; only the write path's own check needs this.
   */
  private Optional<ConfigMap> findLinearizable(String tenantId, String name) {
    return ConfigMapCodec.decodeAll(tenantId, storeClient.listConfigEntriesFor(tenantId)).stream()
        .filter(cm -> cm.name().equals(name))
        .findFirst();
  }
}
