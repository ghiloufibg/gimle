package com.gimle.fafnir.secretmap;

import com.gimle.core.exception.GimleSecretsException;
import com.gimle.fafnir.SecretMetadata;
import com.gimle.fafnir.SecretStore;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.store.LeaseGrant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * SecretMap CRUD, layered entirely over the existing {@link SecretStore} via {@link
 * SecretMapCodec}'s raw-key convention -- this class never touches the underlying store directly,
 * and {@code SecretStore} itself needs no changes to support it. Mirrors {@code
 * com.gimle.controlplane.configmap.ConfigMapStore}'s role for ConfigMap, but each member key keeps
 * its own independent {@code SecretStore} version ledger rather than one JSON envelope per name --
 * a SecretMap has no single "current version" of its own in v1, only a lease serializing writes to
 * one name (see {@link #setMany}).
 */
public final class SecretMapStore {

  // Same bound SecretStore.put/ConfigMapStore.write use for their own optimistic write-verify-
  // retry loops -- generous on purpose, since a real deployment sees at most a couple of
  // colliding writers, not the fully-simultaneous N-way race a stress test deliberately creates.
  private static final int MAX_LEASE_ATTEMPTS = 50;
  // Sized for a full multi-key read-then-write sequence, not just SecretStore's own single-key
  // pointer advance -- a SecretMap bulk write may touch several keys while holding this lease.
  private static final Duration WRITE_LEASE_TTL = Duration.ofSeconds(10);

  private final StoreClient storeClient;
  private final SecretStore secretStore;

  public SecretMapStore(StoreClient storeClient, SecretStore secretStore) {
    this.storeClient = storeClient;
    this.secretStore = secretStore;
  }

  /**
   * Every distinct SecretMap name the tenant owns -- derived purely by filtering {@link
   * SecretStore#list}, no separate backend listing exists or is needed.
   */
  public List<String> listNames(String tenantId) {
    List<String> names = new ArrayList<>();
    for (SecretMetadata meta : secretStore.list(tenantId)) {
      if (SecretMapCodec.isSecretMapKey(meta.key())) {
        String name = SecretMapCodec.nameFromKey(meta.key());
        if (!names.contains(name)) {
          names.add(name);
        }
      }
    }
    return names;
  }

  /**
   * Every member key's metadata for one SecretMap name -- {@code key()} on each result is the plain
   * member key, not the raw prefixed {@code SecretStore} key. Empty if the name is unknown or every
   * one of its keys has been hard-deleted.
   */
  public List<SecretMetadata> getMetadata(String tenantId, String name) {
    List<SecretMetadata> result = new ArrayList<>();
    for (SecretMetadata meta : secretStore.list(tenantId)) {
      if (SecretMapCodec.isSecretMapKey(meta.key())
          && SecretMapCodec.nameFromKey(meta.key()).equals(name)) {
        result.add(
            new SecretMetadata(
                SecretMapCodec.keyFromKey(meta.key()), meta.latestVersion(), meta.deleted()));
      }
    }
    return result;
  }

  /**
   * The value-bearing batch fetch {@code gimle-agent} calls directly to deliver only the SecretMaps
   * a deployment's {@code secretMapRefs} actually named, instead of every secret the tenant owns.
   * One entry per requested name; a name with no keys (unknown, or every key soft-deleted)
   * contributes an empty map rather than being omitted, so a caller can distinguish "no keys" from
   * "name not requested."
   */
  public Map<String, Map<String, byte[]>> getValues(String tenantId, List<String> names) {
    Map<String, Map<String, byte[]>> result = new LinkedHashMap<>();
    for (String name : names) {
      Map<String, byte[]> values = new LinkedHashMap<>();
      for (SecretMetadata meta : getMetadata(tenantId, name)) {
        if (meta.deleted()) {
          continue;
        }
        secretStore
            .get(tenantId, SecretMapCodec.rawKey(name, meta.key()), OptionalInt.empty())
            .ifPresent(value -> values.put(meta.key(), value));
      }
      result.put(name, values);
    }
    return result;
  }

  /**
   * Bulk-sets every key in {@code values} under one SecretMap name, holding a single lease scoped
   * to {@code (tenantId, name)} across the whole batch -- serializes concurrent writers on this
   * SecretMap, but each key is still written via {@link SecretStore#put}'s own independent
   * versioned row, in order, so one key's write failing part-way through never aborts or rolls back
   * the keys already written: every key gets its own {@link SecretMapKeyResult}, success or
   * failure, and the caller sees exactly what landed. (This lease is also where a future
   * group-version stamp belongs, once SecretMap grows a group-level rollback -- deliberately not
   * built here.)
   */
  public List<SecretMapKeyResult> setMany(
      String tenantId, String name, Map<String, byte[]> values) {
    String leaseName = "secretmap-write:" + tenantId + ":" + name;
    // Fresh per call, not a shared field: two concurrent setMany calls on the same name -- exactly
    // the case this lease exists to serialize -- must present distinct holder identities, or the
    // store's own "already held by this holderId" renewal rule would let both hold it at once.
    String holderId = UUID.randomUUID().toString();
    for (int attempt = 0; attempt < MAX_LEASE_ATTEMPTS; attempt++) {
      LeaseGrant lease = storeClient.tryAcquireOrRenewLease(leaseName, holderId, WRITE_LEASE_TTL);
      if (!lease.granted()) {
        continue; // another writer is mid-batch for this exact name; retry once it frees up
      }
      try {
        List<SecretMapKeyResult> results = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : values.entrySet()) {
          try {
            int version =
                secretStore.put(
                    tenantId, SecretMapCodec.rawKey(name, entry.getKey()), entry.getValue());
            results.add(SecretMapKeyResult.ok(entry.getKey(), version));
          } catch (RuntimeException e) {
            results.add(SecretMapKeyResult.failed(entry.getKey(), String.valueOf(e.getMessage())));
          }
        }
        return results;
      } finally {
        storeClient.releaseLease(leaseName, holderId);
      }
    }
    throw GimleSecretsException.secretMapWriteContention(tenantId, name, MAX_LEASE_ATTEMPTS);
  }

  /**
   * Deletes every key under {@code name} -- soft by default, hard when {@code destroy}. Returns
   * {@code false} if the name is unknown (no keys deleted).
   */
  public boolean deleteAll(String tenantId, String name, boolean destroy) {
    List<SecretMetadata> members = getMetadata(tenantId, name);
    if (members.isEmpty()) {
      return false;
    }
    for (SecretMetadata meta : members) {
      String rawKey = SecretMapCodec.rawKey(name, meta.key());
      if (destroy) {
        secretStore.hardDelete(tenantId, rawKey);
      } else {
        secretStore.softDelete(tenantId, rawKey);
      }
    }
    return true;
  }

  /**
   * Deletes a single key within a SecretMap -- soft by default, hard when {@code destroy}. Returns
   * {@code false} if that key doesn't exist under this name.
   */
  public boolean deleteKey(String tenantId, String name, String key, boolean destroy) {
    String rawKey = SecretMapCodec.rawKey(name, key);
    if (!secretStore.exists(tenantId, rawKey)) {
      return false;
    }
    return destroy
        ? secretStore.hardDelete(tenantId, rawKey)
        : secretStore.softDelete(tenantId, rawKey);
  }

  /**
   * One member key's outcome from {@link #setMany} -- exactly one of {@link #version} or {@link
   * #error} is present.
   */
  public record SecretMapKeyResult(String key, OptionalInt version, Optional<String> error) {

    static SecretMapKeyResult ok(String key, int version) {
      return new SecretMapKeyResult(key, OptionalInt.of(version), Optional.empty());
    }

    static SecretMapKeyResult failed(String key, String message) {
      return new SecretMapKeyResult(key, OptionalInt.empty(), Optional.of(message));
    }
  }
}
