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

  /** {@code key@1 .. key@latestVersion} -- every version always exists once claimed. */
  public List<Integer> versions(String tenantId, String key) {
    validateKey(key);
    Meta meta = readMeta(tenantId, key).orElse(Meta.EMPTY);
    List<Integer> versions = new ArrayList<>();
    for (int v = 1; v <= meta.latestVersion(); v++) {
      versions.add(v);
    }
    return versions;
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
  public int put(String tenantId, String key, byte[] plaintext) {
    validateKey(key);
    String leaseName = "fafnir-secret-meta:" + tenantId + ":" + key;
    // Fresh per call (not a shared field): two concurrent #put callers -- the exact case this
    // lease exists to serialize -- must present distinct holder identities, or the store's own
    // "already held by this holderId" renewal rule would let both of them hold the lease at once.
    String holderId = UUID.randomUUID().toString();
    for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt++) {
      Meta before = readMetaLinearizable(tenantId, key).orElse(Meta.EMPTY);
      int next = before.latestVersion() + 1;
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
        if (after.latestVersion() == before.latestVersion()) {
          writeMeta(tenantId, key, new Meta(next, false));
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

  /** Soft delete: every {@code @N} entry stays on disk, recoverable by a future undelete. */
  public boolean softDelete(String tenantId, String key) {
    validateKey(key);
    Optional<Meta> meta = readMeta(tenantId, key);
    if (meta.isEmpty()) {
      return false;
    }
    writeMeta(tenantId, key, new Meta(meta.get().latestVersion(), true));
    return true;
  }

  /** Hard delete ({@code ?destroy=true}): removes {@code @meta} and every {@code @N}. */
  public boolean hardDelete(String tenantId, String key) {
    validateKey(key);
    Optional<Meta> meta = readMeta(tenantId, key);
    if (meta.isEmpty()) {
      return false;
    }
    for (int v = 1; v <= meta.get().latestVersion(); v++) {
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

  /** The {@code key@meta} entry's decoded payload -- never holds secret material itself. */
  private record Meta(int latestVersion, boolean deleted) {

    static final Meta EMPTY = new Meta(0, false);

    static Meta fromBytes(byte[] bytes) {
      Map<String, Object> map =
          Json.asObject(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
      int latestVersion = ((Number) map.get("latestVersion")).intValue();
      boolean deleted = Boolean.TRUE.equals(map.get("deleted"));
      return new Meta(latestVersion, deleted);
    }

    byte[] toBytes() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("latestVersion", latestVersion);
      map.put("deleted", deleted);
      return Json.write(map).getBytes(StandardCharsets.UTF_8);
    }
  }
}
