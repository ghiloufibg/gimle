package com.gimle.fafnir;

import com.gimle.core.config.ConfigEntry;
import com.gimle.core.exception.GimleSecretsException;
import com.gimle.core.protocol.Json;
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
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fafnir's versioned secret storage, layered over the store as a synthetic-key convention. {@code
 * key@meta} is a mutable pointer entry ({@code {latestVersion, deleted}}, plain JSON, never
 * encrypted -- it names a version, it isn't one); {@code key@N} is an immutable, encrypted value
 * entry for version {@code N}. Both are ordinary {@link ConfigEntry} rows in the same {@code
 * gimle-mimir} store {@code gimle-controlplane}'s own {@code /config/*} traffic already uses -- no
 * store schema change, just a key-naming convention only this class ever interprets: Fafnir owns
 * policy, gimle-mimir stays a dumb store. Deliberately a plain Java class, not tied to HTTP,
 * matching {@link FafnirCrypto}'s own separation from {@link FafnirServer}.
 */
public final class SecretStore {

  private static final Logger log = LoggerFactory.getLogger(SecretStore.class);
  private static final String META_SUFFIX = "@meta";
  // Bounds the optimistic write-verify-retry loop in #put below -- contention only ever produces
  // a harmless orphaned key@N entry and a retry, never data loss, but a pathological hot-key race
  // still shouldn't spin forever. Generous on purpose: this is meant as a human/CLI-driven write
  // path, not a hot loop, so a real deployment sees at most a couple of colliding writers, not the
  // fully-simultaneous N-way race a stress test deliberately creates.
  private static final int MAX_WRITE_ATTEMPTS = 50;
  // Held only for the narrow verify-then-advance-@meta step of #put -- see that method's own
  // javadoc for why a lease is needed there at all, despite the write path otherwise being
  // lock-free.
  private static final Duration META_LEASE_TTL = Duration.ofSeconds(5);
  // Deliberately well below ConfigEntry.MAX_VALUE_BYTES: what lands in the store is the
  // *ciphertext* of this plaintext plus its framing, so capping the plaintext at half the storage
  // ceiling guarantees a value accepted here can always actually be persisted, rather than passing
  // this check and then failing deeper down with a confusing message about a size the caller never
  // sent. Generous for everything a secret legitimately holds -- a full PEM chain is a few KiB.
  public static final int MAX_VALUE_BYTES = ConfigEntry.MAX_VALUE_BYTES / 2;

  private final StoreClient storeClient;
  private final FafnirCrypto crypto;

  public SecretStore(StoreClient storeClient, FafnirCrypto crypto) {
    this.storeClient = storeClient;
    this.crypto = crypto;
  }

  /**
   * The list endpoint: every logical secret's metadata for {@code tenantId}, never a value. One
   * corrupted {@code @meta} entry is skipped and logged rather than aborting the whole listing --
   * every other secret the tenant owns is unaffected by one entry's corruption.
   */
  public List<SecretMetadata> list(String tenantId) {
    return decodeAll(tenantId, storeClient.listConfigEntriesFor(tenantId));
  }

  /**
   * Same as {@link #list}, but sourced from a linearizable read -- for a caller building a
   * point-in-time snapshot immediately after its own writes (e.g. {@code
   * com.gimle.fafnir.secretmap.SecretMapStore}'s group-version stamping), where {@link #list}'s
   * plain round-robin read could otherwise land on a replica that hasn't yet caught up to those
   * writes, the same staleness risk {@link #put} already guards its own meta-advance against.
   */
  public List<SecretMetadata> listLinearizable(String tenantId) {
    return decodeAll(tenantId, storeClient.listConfigEntriesForLinearizable(tenantId));
  }

  private List<SecretMetadata> decodeAll(String tenantId, List<ConfigEntry> entries) {
    List<SecretMetadata> result = new ArrayList<>();
    for (ConfigEntry entry : entries) {
      if (!entry.key().endsWith(META_SUFFIX)) {
        continue;
      }
      String key = entry.key().substring(0, entry.key().length() - META_SUFFIX.length());
      Meta meta;
      try {
        meta = Meta.fromBytes(entry.value());
      } catch (RuntimeException e) {
        log.warn("skipping malformed secret metadata for {}/{}: {}", tenantId, key, e.getMessage());
        continue;
      }
      result.add(new SecretMetadata(key, meta.latestVersion(), meta.deleted()));
    }
    return result;
  }

  /**
   * {@code key@1 .. key@highestVersion} -- every version always exists once claimed -- each
   * described by the {@link SecretWrite} that created it: the author principal, the write
   * timestamp, and the declared type. That per-version record is kept on the same single {@code
   * @meta} pointer entry every read path already fetches, so answering "who wrote version 3, and
   * when" costs no extra round trip and needs no correlation against the audit trail.
   */
  public List<SecretVersionInfo> versions(String tenantId, String key) {
    validateKey(key);
    return readMeta(tenantId, key).orElse(Meta.EMPTY).versions();
  }

  /** One version's own metadata, or empty for a key or version number that doesn't exist. */
  public Optional<SecretVersionInfo> versionInfo(String tenantId, String key, int version) {
    validateKey(key);
    return readMeta(tenantId, key).orElse(Meta.EMPTY).versions().stream()
        .filter(info -> info.version() == version)
        .findFirst();
  }

  /**
   * The version number {@link #get} would return with no explicit {@code version} given -- the
   * highest of {@link #versions} <em>except</em> after {@link #undelete} has rewound the pointer to
   * an older one, which {@code versions}'s own gapless {@code 1..highestVersion} listing can no
   * longer be relied on to reveal. Empty for a key with no {@code @meta} entry at all.
   */
  public OptionalInt currentVersion(String tenantId, String key) {
    validateKey(key);
    return readMeta(tenantId, key)
        .map(Meta::latestVersion)
        .map(OptionalInt::of)
        .orElse(OptionalInt.empty());
  }

  /**
   * The read path for an explicit {@code version}: reads {@code key@N} directly, bypassing {@code
   * @meta} entirely (so a historical read is unaffected by concurrent writes advancing {@code
   * latestVersion}, and unaffected by a soft delete -- deletion only ever touches {@code @meta}).
   * Latest-version reads (no {@code version} given) return empty for a soft-deleted or
   * never-written secret, matching Vault KV v2's own "deleted means not found at latest" read
   * behavior.
   */
  public Optional<byte[]> get(String tenantId, String key, OptionalInt version) {
    validateKey(key);
    int targetVersion;
    if (version.isPresent()) {
      targetVersion = version.getAsInt();
    } else {
      Optional<Meta> meta = readMeta(tenantId, key);
      if (meta.isEmpty() || meta.get().deleted() || meta.get().latestVersion() == 0) {
        return Optional.empty();
      }
      targetVersion = meta.get().latestVersion();
    }
    return findEntry(tenantId, versionKey(key, targetVersion)).map(e -> crypto.decrypt(e.value()));
  }

  public boolean exists(String tenantId, String key) {
    validateKey(key);
    return readMeta(tenantId, key).isPresent();
  }

  /**
   * The value-bearing batch read behind a whole-tenant export: every named key's current version,
   * decrypted, with the version metadata it was written under. One store listing serves the whole
   * batch rather than {@link #get}'s own listing per key, so exporting a tenant with a hundred
   * secrets is one round trip instead of a hundred.
   *
   * <p>A key that doesn't exist, is soft-deleted, or has no value entry for its current version is
   * simply absent from the result rather than throwing -- a caller asking for many keys at once
   * shouldn't have one missing name abort the other ninety-nine, and the absence is itself the
   * answer. The caller has already been authorized for this tenant's secrets as a whole; this
   * method performs no authorization of its own, exactly like every other read here.
   */
  public Map<String, SecretValue> getMany(String tenantId, List<String> keys) {
    Map<String, ConfigEntry> byKey = new LinkedHashMap<>();
    for (ConfigEntry entry : storeClient.listConfigEntriesFor(tenantId)) {
      byKey.put(entry.key(), entry);
    }
    Map<String, SecretValue> result = new LinkedHashMap<>();
    for (String key : keys) {
      validateKey(key);
      ConfigEntry metaEntry = byKey.get(metaKey(key));
      if (metaEntry == null) {
        continue;
      }
      Meta meta = decodeMeta(tenantId, key, Optional.of(metaEntry)).orElseThrow();
      if (meta.deleted() || meta.latestVersion() == 0) {
        continue;
      }
      ConfigEntry valueEntry = byKey.get(versionKey(key, meta.latestVersion()));
      if (valueEntry == null) {
        continue;
      }
      SecretVersionInfo info =
          meta.versions().stream()
              .filter(v -> v.version() == meta.latestVersion())
              .findFirst()
              .orElseThrow(
                  () -> GimleSecretsException.versionNotRecoverable(
                      tenantId, key, meta.latestVersion()));
      result.put(key, new SecretValue(crypto.decrypt(valueEntry.value()), info));
    }
    return result;
  }

  /**
   * The write path here: optimistic insert, not a lock -- claiming a candidate {@code key@N} value
   * entry (steps 1-2) is fully lock-free: two writers racing to write the same {@code key@N} slot
   * is a harmless uniqueness collision, since whichever one's write to {@code key@N} lands last
   * simply becomes what that immutable entry holds, and the loser of the race below just discards
   * its own claim and retries with a fresh version number.
   *
   * <p><b>A correction to the write-path sequence below</b>, found empirically (a concurrent-
   * writer test reliably produced duplicate version claims against the plain read-write-reread
   * check as written): comparing {@code @meta}'s value before and after writing {@code key@next}
   * only detects a writer that has *already finished* advancing {@code @meta} -- it does not
   * prevent two writers from both passing that check concurrently, since neither has written {@code
   * @meta} yet at the moment each performs its own "after" read (a classic TOCTOU window). The
   * "not a lock" framing above is right about *claiming a version number*; it doesn't hold for the
   * very last step, *advancing the single mutable pointer that says which version is current*,
   * which is exactly the kind of shared-state race a lock is the correct tool for. So this method
   * takes the narrowest possible lease -- scoped to this one key, held only across the final
   * verify-and-advance step, never around version selection or encryption -- rather than
   * reintroducing a lock around the whole operation that the optimistic approach above was meant
   * to avoid.
   *
   * <p>{@code @meta} is still written last, deliberately: if Fafnir crashes between claiming {@code
   * key@next} and advancing {@code @meta}, {@code @meta} still names the old version and the
   * orphaned {@code key@next} stays invisible to every read path forever -- a crash mid-write can
   * never make {@code @meta} point at a value that didn't finish landing.
   *
   * <p>Both the "before" and "after" reads below use {@link #readMetaLinearizable}, never the
   * plain round-robin {@link #readMeta} every other method here uses: {@code StoreClient}'s reads
   * are deliberately not leader-aware (any replica may answer, possibly one that has not yet
   * replicated a previous {@code put} call's own {@code @meta} advance), so a plain read landing on
   * a lagging replica would see the *same* stale value both times, make the "unchanged since
   * before" check trivially pass, and silently overwrite an existing version instead of creating a
   * new one -- a real, durable corruption, not just a slow-to-converge read, since the write that
   * follows commits under that wrong version number too.
   */
  public int put(String tenantId, String key, byte[] plaintext, SecretWrite write) {
    validateKey(key);
    validateValue(tenantId, key, plaintext, write.type());
    requireTenantExists(tenantId);
    String leaseName = "fafnir-secret-meta:" + tenantId + ":" + key;
    // Fresh per call (not a shared field): two concurrent #put callers -- the exact case this
    // lease exists to serialize -- must present distinct holder identities, or the store's own
    // "already held by this holderId" renewal rule would let both of them hold the lease at once.
    String holderId = UUID.randomUUID().toString();
    for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt++) {
      Meta before = readMetaLinearizable(tenantId, key).orElse(Meta.EMPTY);
      int next = before.highestVersion() + 1;
      byte[] ciphertext = crypto.encrypt(plaintext);
      storeClient.propose(
          new StateMutation.PutConfigEntry(
              new ConfigEntry(tenantId, versionKey(key, next), ciphertext, true)));
      LeaseGrant lease = storeClient.tryAcquireOrRenewLease(leaseName, holderId, META_LEASE_TTL);
      if (!lease.granted()) {
        // Another writer is mid-advance for this exact key -- key@next is a harmless orphan;
        // retry from whatever version is current once the lease frees up.
        continue;
      }
      try {
        Meta after = readMetaLinearizable(tenantId, key).orElse(Meta.EMPTY);
        if (after.highestVersion() == before.highestVersion()) {
          SecretVersionInfo info =
              new SecretVersionInfo(next, write.author(), System.currentTimeMillis(), write.type());
          writeMeta(tenantId, key, after.withNewVersion(info));
          return next;
        }
        // Lost the race to a writer that already finished before this one even acquired the
        // lease -- key@next is now a harmless orphan, retry from the now-current version.
      } finally {
        storeClient.releaseLease(leaseName, holderId);
      }
    }
    throw GimleSecretsException.writeContention(tenantId, key, MAX_WRITE_ATTEMPTS);
  }

  /** Soft delete: every {@code @N} entry stays on disk, recoverable by {@link #undelete}. */
  public boolean softDelete(String tenantId, String key) {
    validateKey(key);
    Optional<Meta> meta = readMeta(tenantId, key);
    if (meta.isEmpty()) {
      return false;
    }
    writeMeta(tenantId, key, meta.get().withDeleted(true));
    return true;
  }

  /**
   * Undelete: clears {@code deleted} on {@code key}'s single {@code @meta} pointer -- {@code
   * deleted} lives there alone, not per {@code @N} entry, so there is nothing to flip on the
   * version data itself, only on the pointer naming which version is current. With no {@code
   * version} given, the pointer's own {@code latestVersion} is left exactly as it was (the version
   * active at the moment {@link #softDelete} was called); with one given, the pointer is moved to
   * name that already-persisted {@code key@N} entry as current instead -- restoring an older
   * version this way, rather than through the {@link #get} + {@link #put} round trip the absence of
   * this method used to force, never mints a new version, and every version's own data is left
   * untouched either way (an old {@code key@N} entry bypassed by a rewind stays exactly as it was,
   * still reachable via an explicit {@link #get} for that version). Returns empty if {@code key}
   * has no {@code @meta} entry at all -- never written, or previously purged entirely by {@link
   * #hardDelete}, whose data is genuinely gone with no undelete path back. Throws if {@code
   * version} (or, absent that, the pointer's own current {@code latestVersion}) names a {@code
   * key@N} entry that doesn't exist -- only possible for a bogus/out-of-range number, since a
   * secret's own version range is otherwise gapless and {@link #hardDelete} always removes {@code
   * @meta} in the same stroke as every {@code @N}, so a partially-purged key is never left behind
   * for this check to catch. Never touches {@code highestVersion} -- rewinding the current pointer
   * must not make {@link #put}'s next-version computation forget about a newer version this call
   * bypassed, or a subsequent {@code put} would silently overwrite it.
   */
  public OptionalInt undelete(String tenantId, String key, OptionalInt version) {
    validateKey(key);
    Optional<Meta> meta = readMeta(tenantId, key);
    if (meta.isEmpty()) {
      return OptionalInt.empty();
    }
    int targetVersion = version.orElseGet(meta.get()::latestVersion);
    if (targetVersion < 1 || findEntry(tenantId, versionKey(key, targetVersion)).isEmpty()) {
      throw GimleSecretsException.versionNotRecoverable(tenantId, key, targetVersion);
    }
    writeMeta(tenantId, key, meta.get().withCurrentVersion(targetVersion));
    return OptionalInt.of(targetVersion);
  }

  /** Hard delete ({@code ?destroy=true}): removes {@code @meta} and every {@code @N}. */
  public boolean hardDelete(String tenantId, String key) {
    validateKey(key);
    Optional<Meta> meta = readMeta(tenantId, key);
    if (meta.isEmpty()) {
      return false;
    }
    for (int v = 1; v <= meta.get().highestVersion(); v++) {
      storeClient.propose(new StateMutation.RemoveConfigEntry(tenantId, versionKey(key, v)));
    }
    storeClient.propose(new StateMutation.RemoveConfigEntry(tenantId, metaKey(key)));
    return true;
  }

  private Optional<Meta> readMeta(String tenantId, String key) {
    return decodeMeta(tenantId, key, findEntry(tenantId, metaKey(key)));
  }

  /** Only {@link #put}'s own before/after check needs this -- see that method's own javadoc. */
  private Optional<Meta> readMetaLinearizable(String tenantId, String key) {
    return decodeMeta(tenantId, key, findEntryLinearizable(tenantId, metaKey(key)));
  }

  private Optional<Meta> decodeMeta(String tenantId, String key, Optional<ConfigEntry> entry) {
    return entry.map(
        e -> {
          try {
            return Meta.fromBytes(e.value());
          } catch (RuntimeException ex) {
            throw GimleSecretsException.malformedMetaEntry(tenantId, key, ex);
          }
        });
  }

  private void writeMeta(String tenantId, String key, Meta meta) {
    storeClient.propose(
        new StateMutation.PutConfigEntry(
            new ConfigEntry(tenantId, metaKey(key), meta.toBytes(), false)));
  }

  private Optional<ConfigEntry> findEntry(String tenantId, String rawKey) {
    return storeClient.listConfigEntriesFor(tenantId).stream()
        .filter(e -> e.key().equals(rawKey))
        .findFirst();
  }

  private Optional<ConfigEntry> findEntryLinearizable(String tenantId, String rawKey) {
    return storeClient.listConfigEntriesForLinearizable(tenantId).stream()
        .filter(e -> e.key().equals(rawKey))
        .findFirst();
  }

  /**
   * A write against a tenant nothing ever registered has no natural "not found" outcome the way a
   * read does (a read just answers empty), so it's rejected outright here rather than silently
   * persisting a secret under a tenant id the rest of the system -- RBAC, quota, the console's own
   * tenant picker -- has never heard of.
   */
  private void requireTenantExists(String tenantId) {
    if (storeClient.getTenant(tenantId).isEmpty()) {
      throw GimleSecretsException.unknownTenant(tenantId);
    }
  }

  private static String metaKey(String key) {
    return key + META_SUFFIX;
  }

  private static String versionKey(String key, int version) {
    return key + "@" + version;
  }

  /**
   * Flat namespace: {@code /} and {@code @} both disallowed. {@code /} because Gimlé's RBAC model
   * (tenant-scoped, not path-scoped) has no enforcement mechanism today that would ever consume a
   * hierarchical path segment; {@code @} because it's this scheme's own reserved separator -- a raw
   * key containing it could collide with a synthetic {@code @meta}/{@code @N} suffix and break the
   * split back apart.
   */
  /**
   * The two checks a plaintext must clear before anything is encrypted: it fits, and it actually
   * has the shape it claims. Both run before the first store round trip of {@link #put} -- a value
   * that will be refused must never leave a half-claimed {@code key@N} entry behind, and must never
   * be encrypted at all.
   */
  private static void validateValue(
      String tenantId, String key, byte[] plaintext, SecretType type) {
    if (plaintext == null) {
      throw new IllegalArgumentException("value must not be null");
    }
    if (plaintext.length > MAX_VALUE_BYTES) {
      throw GimleSecretsException.valueTooLarge(
          tenantId, key, plaintext.length, MAX_VALUE_BYTES);
    }
    type.validate(tenantId, key, plaintext);
  }

  private static void validateKey(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
    if (key.contains("/") || key.contains("@")) {
      throw new IllegalArgumentException(
          "key must not contain '/' or '@' (reserved for Fafnir's own version/meta suffixes): "
              + key);
    }
  }

  /**
   * The {@code key@meta} entry's decoded payload -- never holds secret material itself. {@code
   * latestVersion} and {@code highestVersion} are deliberately distinct fields, not one: {@code
   * latestVersion} is the current pointer {@link #get} reads by default and {@link #undelete} may
   * rewind to an older number, while {@code highestVersion} is the count of {@code key@N} entries
   * actually claimed on disk -- only ever advanced by {@link #put}, never rewound. Before {@link
   * #undelete} existed the two always moved together, so one field sufficed; once undelete can move
   * {@code latestVersion} backward without touching the data, {@link #versions} and {@link
   * #hardDelete} (both of which must still account for every {@code key@N} entry that physically
   * exists, not just the ones reachable from the current pointer) need the un-rewound count, and
   * {@link #put} needs it too when computing the next version number -- computing "next" from the
   * current pointer instead would silently overwrite an existing newer version's data after a
   * rewind.
   *
   * <p>{@code versions} is the append-only record of how each {@code key@N} came to exist -- author,
   * write timestamp, declared type -- and lives here, on the one mutable pointer entry, rather than
   * beside each immutable {@code key@N} value entry: it is read on every listing, so keeping it here
   * answers "who wrote version 3, and when" from the single entry the read path already fetches
   * instead of one extra round trip per version. Nothing in it is secret material, which is why it
   * can sit on the unencrypted pointer entry at all.
   */
  private record Meta(
      int latestVersion, int highestVersion, boolean deleted, List<SecretVersionInfo> versions) {

    static final Meta EMPTY = new Meta(0, 0, false, List.of());

    Meta {
      versions = List.copyOf(versions);
    }

    /** The pointer after a {@link #put} claimed {@code info}'s version -- appended, never edited. */
    Meta withNewVersion(SecretVersionInfo info) {
      List<SecretVersionInfo> appended = new ArrayList<>(versions);
      appended.add(info);
      return new Meta(info.version(), info.version(), false, appended);
    }

    Meta withDeleted(boolean nowDeleted) {
      return new Meta(latestVersion, highestVersion, nowDeleted, versions);
    }

    /** {@link #undelete}'s rewind: moves the current pointer, never the recorded write history. */
    Meta withCurrentVersion(int version) {
      return new Meta(version, highestVersion, false, versions);
    }

    static Meta fromBytes(byte[] bytes) {
      Map<String, Object> map =
          Json.asObject(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
      int latestVersion = ((Number) map.get("latestVersion")).intValue();
      int highestVersion = ((Number) map.get("highestVersion")).intValue();
      boolean deleted = Boolean.TRUE.equals(map.get("deleted"));
      List<SecretVersionInfo> versions = new ArrayList<>();
      for (Map<String, Object> raw : Json.asObjectList(map.get("versions"))) {
        versions.add(
            new SecretVersionInfo(
                ((Number) raw.get("version")).intValue(),
                (String) raw.get("author"),
                ((Number) raw.get("writtenAtEpochMilli")).longValue(),
                SecretType.fromWire((String) raw.get("type"))));
      }
      return new Meta(latestVersion, highestVersion, deleted, versions);
    }

    byte[] toBytes() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("latestVersion", latestVersion);
      map.put("highestVersion", highestVersion);
      map.put("deleted", deleted);
      List<Map<String, Object>> encoded = new ArrayList<>();
      for (SecretVersionInfo info : versions) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("version", info.version());
        one.put("author", info.author());
        one.put("writtenAtEpochMilli", info.writtenAtEpochMilli());
        one.put("type", info.type().name());
        encoded.add(one);
      }
      map.put("versions", encoded);
      return Json.write(map).getBytes(StandardCharsets.UTF_8);
    }
  }
}
