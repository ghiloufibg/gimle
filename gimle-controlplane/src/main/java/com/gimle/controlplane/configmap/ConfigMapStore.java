package com.gimle.controlplane.configmap;

import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.store.LeaseGrant;
import java.time.Duration;
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

  public void delete(String tenantId, String name) {
    storeClient.propose(new StateMutation.RemoveConfigEntry(tenantId, ConfigMapCodec.keyFor(name)));
  }

  /**
   * The write path shared by {@link #put} and {@link #patch}: one lease, acquired first and held
   * across the entire read-check-propose sequence, not just a narrow final verify-and-advance step
   * the way {@code SecretStore.put}'s own lease is scoped. That narrower shape exists there to
   * protect a two-phase "claim an immutable version, then advance a separate mutable pointer"
   * write; a ConfigMap is one mutable row with no separate immutable history to protect, so there
   * is no second phase to leave outside the lease -- covering the whole read-check-write critical
   * section with a single lease is both simpler and exactly as safe.
   */
  private ConfigMapWriteResult write(
      String tenantId,
      String name,
      OptionalInt expectedVersion,
      Function<Optional<ConfigMap>, Map<String, String>> computeNewData) {
    String leaseName = "configmap-write:" + tenantId + ":" + name;
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
        int newVersion = currentVersion + 1;
        storeClient.propose(
            new StateMutation.PutConfigEntry(
                ConfigMapCodec.encode(new ConfigMap(tenantId, name, newVersion, newData))));
        return new ConfigMapWriteResult.Written(newVersion);
      } finally {
        storeClient.releaseLease(leaseName, holderId);
      }
    }
    return new ConfigMapWriteResult.WriteContention(MAX_WRITE_ATTEMPTS);
  }

  /**
   * Leader-routed, linearizable read for the write path's own "before" snapshot -- a plain
   * round-robin read could land on a lagging replica and see a stale version, making the {@code
   * expectedVersion} check pass when it shouldn't. Ordinary {@link #get}/{@link #list}/{@link
   * #getMany} reads stay plain round-robin; only the write path's own check needs this.
   */
  private Optional<ConfigMap> findLinearizable(String tenantId, String name) {
    return ConfigMapCodec.decodeAll(
            tenantId, storeClient.listConfigEntriesForLinearizable(tenantId))
        .stream()
        .filter(cm -> cm.name().equals(name))
        .findFirst();
  }
}
