package com.gimle.fafnir.secretmap;

import com.gimle.core.config.ConfigEntry;
import com.gimle.core.exception.GimleSecretsException;
import com.gimle.core.protocol.Json;
import com.gimle.fafnir.SecretMetadata;
import com.gimle.fafnir.SecretStore;
import com.gimle.fafnir.SecretWrite;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.store.LeaseGrant;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * SecretMap CRUD, layered entirely over the existing {@link SecretStore} via {@link
 * SecretMapCodec}'s raw-key convention -- this class never touches the underlying store directly
 * for member-key values, and {@code SecretStore} itself needs no changes to support it. Mirrors
 * {@code com.gimle.controlplane.configmap.ConfigMapStore}'s role for ConfigMap, but each member key
 * keeps its own independent {@code SecretStore} version ledger rather than one JSON envelope per
 * name -- what makes a SecretMap one coherent object is the group-version ledger this class
 * maintains on the side (see {@link #stampGroupVersion}), not a shared row.
 *
 * <p>Group-version snapshots are stored as plain, unencrypted {@link ConfigEntry} rows written
 * directly via {@link StoreClient#propose}, under a key family ({@code "secretmap-group:"}) that
 * never ends in {@code SecretStore}'s own {@code @meta} suffix -- so they're structurally invisible
 * to every {@code SecretStore} read path (including a flat {@code /secrets/*} listing) with no
 * reserved-prefix guard needed, unlike {@link SecretMapCodec}'s own {@code secretmap:} data prefix,
 * which flat writes could otherwise collide with. They hold only key names and version numbers,
 * never secret material, matching why {@code key@meta} itself is unencrypted.
 *
 * <p>They're also filed under a synthetic tenant id ({@link #metaTenantId}) rather than {@code
 * tenantId} itself, deliberately: {@code ConfigEntry} has no namespace of its own beyond {@code
 * (tenantId, key)}, and the real tenant's own {@code (tenantId, key)} space is exactly what {@code
 * gimle-controlplane}'s generic {@code /config/{tenantId}/*} surface reads and writes directly
 * against the store -- a plain key-prefix convention within that same space (the way {@link
 * SecretMapCodec}'s own {@code secretmap:} prefix works) only ever hides a row from a caller that
 * happens to apply the right filter, it can't stop a caller from deleting it by exact key. Filing
 * these rows under a tenant id no real tenant can ever be, instead, makes them genuinely
 * unaddressable through {@code /config/{tenantId}/*} for the real {@code tenantId} -- not filtered
 * out, structurally absent from that keyspace.
 */
public final class SecretMapStore {

  // Same bound SecretStore.put/ConfigMapStore.write use for their own optimistic write-verify-
  // retry loops -- generous on purpose, since a real deployment sees at most a couple of
  // colliding writers, not the fully-simultaneous N-way race a stress test deliberately creates.
  private static final int MAX_LEASE_ATTEMPTS = 50;
  // Sized for a full multi-key read-then-write sequence, not just SecretStore's own single-key
  // pointer advance -- a SecretMap bulk write may touch several keys while holding this lease.
  private static final Duration WRITE_LEASE_TTL = Duration.ofSeconds(10);
  private static final String GROUP_VERSION_KEY_PREFIX = "secretmap-group:";
  // No real tenant id can ever equal this, since real tenant ids are created through
  // gimle-controlplane's own tenant API, which never produces one containing ':' followed by this
  // exact reserved marker -- see the class javadoc for why group-version rows live under this
  // synthetic id rather than the real tenantId.
  private static final String META_TENANT_PREFIX = "gimle-internal:secretmap-meta:";

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
    return filterMembers(secretStore.list(tenantId), name);
  }

  /**
   * Same as {@link #getMetadata}, but linearizable -- used only when building a group-version
   * snapshot, where a plain read landing on a lagging replica could record the wrong per-key
   * version for a write this same call just made (see the class javadoc).
   */
  private List<SecretMetadata> getMetadataLinearizable(String tenantId, String name) {
    return filterMembers(secretStore.listLinearizable(tenantId), name);
  }

  private static List<SecretMetadata> filterMembers(List<SecretMetadata> all, String name) {
    List<SecretMetadata> result = new ArrayList<>();
    for (SecretMetadata meta : all) {
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
   * failure, and the caller sees exactly what landed. A group version is stamped once at the end
   * (see {@link #stampGroupVersion}) recording every member key's resulting state -- skipped if
   * every key in this batch failed, since nothing actually changed.
   *
   * <p>{@code write} is relayed straight through to each member key's own {@link SecretStore#put},
   * so a SecretMap member records who wrote it exactly like a flat secret does; a member key has no
   * declared shape of its own, so callers pass {@link SecretWrite#opaqueBy}.
   */
  public List<SecretMapKeyResult> setMany(
      String tenantId, String name, Map<String, byte[]> values, SecretWrite write) {
    return withWriteLease(
        tenantId,
        name,
        () -> {
          List<SecretMapKeyResult> results = new ArrayList<>();
          for (Map.Entry<String, byte[]> entry : values.entrySet()) {
            try {
              int version =
                  secretStore.put(
                      tenantId,
                      SecretMapCodec.rawKey(name, entry.getKey()),
                      entry.getValue(),
                      write);
              results.add(SecretMapKeyResult.ok(entry.getKey(), version));
            } catch (RuntimeException e) {
              results.add(
                  SecretMapKeyResult.failed(entry.getKey(), String.valueOf(e.getMessage())));
            }
          }
          if (results.stream().anyMatch(r -> r.version().isPresent())) {
            stampGroupVersion(tenantId, name, OptionalInt.empty());
          }
          return results;
        });
  }

  /**
   * Full replace: like {@link #setMany}, but every currently-live member key not named in {@code
   * values} is soft-deleted too, so the resulting key set is exactly {@code values}' key set -- the
   * {@code secretmap replace} counterpart to {@link #setMany}'s merge-only {@code secretmap set}.
   * Held under the same single write lease as {@link #setMany}, and each touched key still gets its
   * own independent {@link SecretMapKeyResult}: a key that fails to write, or a stale member that
   * fails to delete, is reported failed rather than aborting the keys already touched. Passing an
   * empty {@code values} clears the SecretMap entirely, the same way {@code ConfigMapStore#put}
   * with an empty data map overwrites to empty. One group version is stamped at the end reflecting
   * the final state, exactly as {@link #setMany} does -- skipped only if nothing actually changed
   * (an empty {@code values} against an already-empty SecretMap).
   */
  public List<SecretMapKeyResult> replaceAll(
      String tenantId, String name, Map<String, byte[]> values, SecretWrite write) {
    return withWriteLease(
        tenantId,
        name,
        () -> {
          List<SecretMapKeyResult> results = new ArrayList<>();
          for (Map.Entry<String, byte[]> entry : values.entrySet()) {
            try {
              int version =
                  secretStore.put(
                      tenantId,
                      SecretMapCodec.rawKey(name, entry.getKey()),
                      entry.getValue(),
                      write);
              results.add(SecretMapKeyResult.ok(entry.getKey(), version));
            } catch (RuntimeException e) {
              results.add(
                  SecretMapKeyResult.failed(entry.getKey(), String.valueOf(e.getMessage())));
            }
          }
          for (SecretMetadata meta : getMetadataLinearizable(tenantId, name)) {
            if (meta.deleted() || values.containsKey(meta.key())) {
              continue;
            }
            String rawKey = SecretMapCodec.rawKey(name, meta.key());
            try {
              if (!secretStore.softDelete(tenantId, rawKey)) {
                throw new IllegalStateException("key no longer exists to remove");
              }
              results.add(SecretMapKeyResult.ok(meta.key(), meta.latestVersion()));
            } catch (RuntimeException e) {
              results.add(SecretMapKeyResult.failed(meta.key(), String.valueOf(e.getMessage())));
            }
          }
          if (results.stream().anyMatch(r -> r.version().isPresent())) {
            stampGroupVersion(tenantId, name, OptionalInt.empty());
          }
          return results;
        });
  }

  /**
   * Deletes every key under {@code name} -- soft by default, hard when {@code destroy}. Returns
   * {@code false} if the name is unknown (no keys deleted). Lease-guarded the same as {@link
   * #setMany}. A soft delete is group-version-stamped like any other mutation to the group's
   * recorded state, so it's reflected in rollback history; a hard delete instead purges every group
   * version this name ever had (see {@link #purgeGroupVersions}) rather than stamping one more --
   * matching {@code SecretStore#hardDelete}'s own "genuine purge, not a tombstone" semantics for a
   * plain secret's own version history.
   */
  public boolean deleteAll(String tenantId, String name, boolean destroy) {
    return withWriteLease(
        tenantId,
        name,
        () -> {
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
          if (destroy) {
            purgeGroupVersions(tenantId, name);
          } else {
            stampGroupVersion(tenantId, name, OptionalInt.empty());
          }
          return true;
        });
  }

  /**
   * Deletes a single key within a SecretMap -- soft by default, hard when {@code destroy}. Returns
   * {@code false} if that key doesn't exist under this name. Lease-guarded and group-version-
   * stamped for the same reason {@link #deleteAll} is.
   */
  public boolean deleteKey(String tenantId, String name, String key, boolean destroy) {
    return withWriteLease(
        tenantId,
        name,
        () -> {
          String rawKey = SecretMapCodec.rawKey(name, key);
          if (!secretStore.exists(tenantId, rawKey)) {
            return false;
          }
          boolean deleted =
              destroy
                  ? secretStore.hardDelete(tenantId, rawKey)
                  : secretStore.softDelete(tenantId, rawKey);
          if (deleted) {
            stampGroupVersion(tenantId, name, OptionalInt.empty());
          }
          return deleted;
        });
  }

  /**
   * Every group version ever stamped for {@code name}, oldest first. A group version is created by
   * {@link #setMany}/{@link #deleteAll}/{@link #deleteKey} on an actual change, and by {@link
   * #rollback} itself (forward-only -- rollback never rewrites an existing group version).
   */
  public List<SecretMapGroupVersion> listGroupVersions(String tenantId, String name) {
    List<SecretMapGroupVersion> result = new ArrayList<>();
    for (ConfigEntry entry : storeClient.listConfigEntriesFor(metaTenantId(tenantId))) {
      if (isGroupVersionKey(entry.key(), name)) {
        result.add(decodeGroupVersion(entry.value()));
      }
    }
    result.sort((a, b) -> Integer.compare(a.groupVersion(), b.groupVersion()));
    return result;
  }

  /**
   * Restores the SecretMap's exact key set as of {@code targetGroupVersion}: every key that
   * snapshot recorded is restored to that snapshot's content (for a live key) or deleted state (for
   * one recorded as deleted) -- never in place, a restored key's content is written as a brand-new
   * {@code SecretStore} version -- and every key that exists now but wasn't part of that snapshot
   * (added by a write after {@code targetGroupVersion}) is itself soft-deleted, since a true
   * rollback restores the group's membership as it was, not just the keys the target happened to
   * mention. The rollback itself is recorded as a new, forward-only group version (never rewrites
   * {@code targetGroupVersion} or anything after it), the same "restore = re-apply as a new
   * revision" pattern {@code gimle-hilmir}'s own release rollback uses. One key's history being
   * unrecoverable (only possible if it was hard-deleted since) is reported as that key's own
   * failure, not a thrown exception, so it never blocks its siblings' restore.
   */
  public RollbackOutcome rollback(
      String tenantId, String name, int targetGroupVersion, SecretWrite write) {
    return withWriteLease(
        tenantId,
        name,
        () -> {
          Optional<SecretMapGroupVersion> target =
              findGroupVersion(tenantId, name, targetGroupVersion);
          if (target.isEmpty()) {
            return new RollbackOutcome.TargetNotFound();
          }
          // Read once, before any of this rollback's own mutations: a soft delete never changes
          // a key's latestVersion, so the version to report for a deleted-state restore is
          // whichever version the key already had going in -- not the (possibly stale, if other
          // writes landed since) version this target snapshot itself recorded.
          Map<String, SecretMetadata> current =
              getMetadataLinearizable(tenantId, name).stream()
                  .collect(Collectors.toMap(SecretMetadata::key, m -> m));
          List<SecretMapKeyResult> results = new ArrayList<>();
          for (Map.Entry<String, SecretMapKeySnapshot> entry : target.get().keys().entrySet()) {
            String key = entry.getKey();
            SecretMapKeySnapshot snapshot = entry.getValue();
            String rawKey = SecretMapCodec.rawKey(name, key);
            try {
              if (snapshot.deleted()) {
                SecretMetadata currentMeta = current.get(key);
                if (currentMeta == null || !secretStore.softDelete(tenantId, rawKey)) {
                  throw new IllegalStateException(
                      "key no longer exists to restore its deleted state");
                }
                results.add(SecretMapKeyResult.ok(key, currentMeta.latestVersion()));
              } else {
                byte[] oldValue =
                    secretStore
                        .get(tenantId, rawKey, OptionalInt.of(snapshot.version()))
                        .orElseThrow(
                            () ->
                                new IllegalStateException(
                                    "version " + snapshot.version() + " no longer recoverable"));
                int newVersion = secretStore.put(tenantId, rawKey, oldValue, write);
                results.add(SecretMapKeyResult.ok(key, newVersion));
              }
            } catch (RuntimeException e) {
              results.add(SecretMapKeyResult.failed(key, String.valueOf(e.getMessage())));
            }
          }
          // Keys that exist now but the target snapshot never recorded were added by some later
          // write -- restoring the group's exact prior membership means removing them too, not
          // just leaving them untouched because the target didn't mention them.
          Set<String> targetKeys = target.get().keys().keySet();
          for (Map.Entry<String, SecretMetadata> entry : current.entrySet()) {
            String key = entry.getKey();
            SecretMetadata meta = entry.getValue();
            if (targetKeys.contains(key) || meta.deleted()) {
              continue;
            }
            String rawKey = SecretMapCodec.rawKey(name, key);
            try {
              if (!secretStore.softDelete(tenantId, rawKey)) {
                throw new IllegalStateException("key no longer exists to remove");
              }
              results.add(SecretMapKeyResult.ok(key, meta.latestVersion()));
            } catch (RuntimeException e) {
              results.add(SecretMapKeyResult.failed(key, String.valueOf(e.getMessage())));
            }
          }
          int newGroupVersion =
              stampGroupVersion(tenantId, name, OptionalInt.of(targetGroupVersion));
          return new RollbackOutcome.Applied(results, newGroupVersion);
        });
  }

  /**
   * Records the SecretMap's current full member state (every live or deleted key, as {@link
   * #getMetadataLinearizable} sees it right now) as a brand-new group version -- called while still
   * holding the {@code (tenantId, name)} write lease, so "current" here means "as of the write(s)
   * this exact call just made," not a value racing some other writer. {@code rollbackOf} is present
   * only when this stamp is itself the result of {@link #rollback}.
   */
  private int stampGroupVersion(String tenantId, String name, OptionalInt rollbackOf) {
    Map<String, SecretMapKeySnapshot> keys = new LinkedHashMap<>();
    for (SecretMetadata meta : getMetadataLinearizable(tenantId, name)) {
      keys.put(meta.key(), new SecretMapKeySnapshot(meta.latestVersion(), meta.deleted()));
    }
    int next = nextGroupVersion(tenantId, name);
    SecretMapGroupVersion snapshot = new SecretMapGroupVersion(next, keys, rollbackOf);
    storeClient.propose(
        new StateMutation.PutConfigEntry(
            new ConfigEntry(
                metaTenantId(tenantId),
                groupVersionKey(name, next),
                encodeGroupVersion(snapshot),
                /* encrypted= */ false)));
    return next;
  }

  private int nextGroupVersion(String tenantId, String name) {
    int max = 0;
    for (ConfigEntry entry : storeClient.listConfigEntriesFor(metaTenantId(tenantId))) {
      if (isGroupVersionKey(entry.key(), name)) {
        max = Math.max(max, decodeGroupVersion(entry.value()).groupVersion());
      }
    }
    return max + 1;
  }

  /**
   * Removes every group version ever stamped for {@code name} -- the destroy-time counterpart to
   * {@code SecretStore#hardDelete}'s own per-member-key purge, called by {@link #deleteAll} instead
   * of stamping one more (tombstone) group version, so a destroyed SecretMap's {@link
   * #listGroupVersions} comes back empty rather than still listing every version it ever had.
   */
  private void purgeGroupVersions(String tenantId, String name) {
    for (ConfigEntry entry : storeClient.listConfigEntriesFor(metaTenantId(tenantId))) {
      if (isGroupVersionKey(entry.key(), name)) {
        storeClient.propose(
            new StateMutation.RemoveConfigEntry(metaTenantId(tenantId), entry.key()));
      }
    }
  }

  // The synthetic tenant id group-version rows are filed under -- see the class javadoc for why.
  private static String metaTenantId(String tenantId) {
    return META_TENANT_PREFIX + tenantId;
  }

  private Optional<SecretMapGroupVersion> findGroupVersion(
      String tenantId, String name, int groupVersion) {
    String rawKey = groupVersionKey(name, groupVersion);
    for (ConfigEntry entry : storeClient.listConfigEntriesFor(metaTenantId(tenantId))) {
      if (entry.key().equals(rawKey)) {
        return Optional.of(decodeGroupVersion(entry.value()));
      }
    }
    return Optional.empty();
  }

  private static String groupVersionKey(String name, int groupVersion) {
    return GROUP_VERSION_KEY_PREFIX + name + ":" + groupVersion;
  }

  // "secretmap-group:{name}:" is this convention's own separator between the name and the group
  // version number -- checking the prefix this way (rather than parsing and comparing the name
  // half back out) sidesteps ever having to worry about a name containing what would otherwise
  // look like a boundary.
  private static boolean isGroupVersionKey(String rawKey, String name) {
    return rawKey.startsWith(GROUP_VERSION_KEY_PREFIX + name + ":");
  }

  private static SecretMapGroupVersion decodeGroupVersion(byte[] bytes) {
    Map<String, Object> map = Json.asObject(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
    int groupVersion = ((Number) map.get("groupVersion")).intValue();
    Map<String, SecretMapKeySnapshot> keys = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : Json.asObject(map.get("keys")).entrySet()) {
      Map<String, Object> raw = Json.asObject(entry.getValue());
      keys.put(
          entry.getKey(),
          new SecretMapKeySnapshot(
              ((Number) raw.get("version")).intValue(), Boolean.TRUE.equals(raw.get("deleted"))));
    }
    Object rollbackOf = map.get("rollbackOfGroupVersion");
    OptionalInt rollbackOfGroupVersion =
        rollbackOf instanceof Number n ? OptionalInt.of(n.intValue()) : OptionalInt.empty();
    return new SecretMapGroupVersion(groupVersion, keys, rollbackOfGroupVersion);
  }

  private static byte[] encodeGroupVersion(SecretMapGroupVersion snapshot) {
    Map<String, Object> keys = new LinkedHashMap<>();
    for (Map.Entry<String, SecretMapKeySnapshot> entry : snapshot.keys().entrySet()) {
      keys.put(
          entry.getKey(),
          Map.of("version", entry.getValue().version(), "deleted", entry.getValue().deleted()));
    }
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("groupVersion", snapshot.groupVersion());
    map.put("keys", keys);
    snapshot.rollbackOfGroupVersion().ifPresent(v -> map.put("rollbackOfGroupVersion", v));
    return Json.write(map).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Acquires the {@code (tenantId, name)} write lease, runs {@code body} while holding it, and
   * releases it in a {@code finally} -- the shared retry/lease-acquire loop behind {@link
   * #setMany}, {@link #deleteAll}, {@link #deleteKey}, and {@link #rollback}, all of which must
   * serialize against each other on the same SecretMap name for the group-version sequence to stay
   * correct.
   */
  private <T> T withWriteLease(String tenantId, String name, Supplier<T> body) {
    String leaseName = "secretmap-write:" + tenantId + ":" + name;
    // Fresh per call, not a shared field: two concurrent callers on the same name -- exactly the
    // case this lease exists to serialize -- must present distinct holder identities, or the
    // store's own "already held by this holderId" renewal rule would let both hold it at once.
    String holderId = UUID.randomUUID().toString();
    for (int attempt = 0; attempt < MAX_LEASE_ATTEMPTS; attempt++) {
      LeaseGrant lease = storeClient.tryAcquireOrRenewLease(leaseName, holderId, WRITE_LEASE_TTL);
      if (!lease.granted()) {
        continue; // another writer is mid-operation for this exact name; retry once it frees up
      }
      try {
        return body.get();
      } finally {
        storeClient.releaseLease(leaseName, holderId);
      }
    }
    throw GimleSecretsException.secretMapWriteContention(tenantId, name, MAX_LEASE_ATTEMPTS);
  }

  /**
   * One member key's outcome from {@link #setMany} or {@link #rollback} -- exactly one of {@link
   * #version} or {@link #error} is present.
   */
  public record SecretMapKeyResult(String key, OptionalInt version, Optional<String> error) {

    static SecretMapKeyResult ok(String key, int version) {
      return new SecretMapKeyResult(key, OptionalInt.of(version), Optional.empty());
    }

    static SecretMapKeyResult failed(String key, String message) {
      return new SecretMapKeyResult(key, OptionalInt.empty(), Optional.of(message));
    }
  }

  /** One member key's recorded state within a {@link SecretMapGroupVersion} snapshot. */
  public record SecretMapKeySnapshot(int version, boolean deleted) {}

  /**
   * One stamped group version of a SecretMap: the full member state at the moment it was stamped.
   * {@code rollbackOfGroupVersion} is present only when this snapshot was itself produced by {@link
   * #rollback}, recording which earlier group version it restored.
   */
  public record SecretMapGroupVersion(
      int groupVersion,
      Map<String, SecretMapKeySnapshot> keys,
      OptionalInt rollbackOfGroupVersion) {}

  /**
   * The result of {@link #rollback}: either it found and applied {@code targetGroupVersion}, or
   * didn't.
   */
  public sealed interface RollbackOutcome {
    record Applied(List<SecretMapKeyResult> results, int newGroupVersion)
        implements RollbackOutcome {}

    record TargetNotFound() implements RollbackOutcome {}
  }
}
