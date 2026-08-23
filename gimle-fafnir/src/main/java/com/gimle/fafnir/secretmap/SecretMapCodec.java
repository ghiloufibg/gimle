package com.gimle.fafnir.secretmap;

/**
 * The synthetic-key convention layered over {@link com.gimle.fafnir.SecretStore}'s own flat key
 * namespace: a SecretMap named {@code name} for a tenant, holding a key {@code key}, is an ordinary
 * {@code SecretStore} row addressed by the raw key {@code "secretmap:" + name + ":" + key} --
 * {@code SecretStore} itself needs no changes at all, since {@code :} is not one of the two
 * characters ({@code /} and {@code @}) {@link com.gimle.fafnir.SecretStore#get} already reserves.
 * Each key keeps its own independent {@code key@meta}/{@code key@N} version ledger exactly as if it
 * had been {@code secret set} directly under this raw name -- grouping is purely a naming
 * convention this class is the one place that interprets, mirroring {@code
 * com.gimle.controlplane.configmap.ConfigMapCodec}'s identical role for ConfigMap's own {@code
 * configmap:} prefix.
 */
public final class SecretMapCodec {

  public static final String KEY_PREFIX = "secretmap:";

  private SecretMapCodec() {}

  /**
   * The raw {@code SecretStore} key for one member of a SecretMap. {@code key} must not itself
   * contain {@code :} -- enforced here, not in {@code SecretStore}, since {@code :} is this
   * convention's own reserved separator, not a general reserved character.
   */
  public static String rawKey(String name, String key) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("SecretMap name must not be blank");
    }
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("SecretMap key must not be blank");
    }
    if (name.contains(":") || key.contains(":")) {
      throw new IllegalArgumentException(
          "SecretMap name and key must not contain ':' (reserved for this convention's own"
              + " separator): "
              + name
              + "/"
              + key);
    }
    return KEY_PREFIX + name + ":" + key;
  }

  /**
   * {@code true} for a raw {@code SecretStore} key this convention owns -- reused by {@code
   * FafnirServer} to reject a flat {@code /secrets/*} write/delete against a reserved key and to
   * filter these rows out of a plain {@code /secrets/*} listing, the same way {@code
   * ConfigMapCodec.isConfigMapKey} is already used against {@code /config/*}.
   */
  public static boolean isSecretMapKey(String rawKey) {
    return rawKey.startsWith(KEY_PREFIX);
  }

  /** The SecretMap name half of a raw key produced by {@link #rawKey}. */
  public static String nameFromKey(String rawKey) {
    int split = splitIndex(rawKey);
    return rawKey.substring(KEY_PREFIX.length(), split);
  }

  /** The member-key half of a raw key produced by {@link #rawKey}. */
  public static String keyFromKey(String rawKey) {
    int split = splitIndex(rawKey);
    return rawKey.substring(split + 1);
  }

  // The name/key boundary is the *last* ':' in the raw key, not the first -- KEY_PREFIX itself
  // ends right before the name starts, and neither half may contain ':' (see rawKey above), so
  // the last (and only remaining) ':' is unambiguously the boundary.
  private static int splitIndex(String rawKey) {
    if (!isSecretMapKey(rawKey)) {
      throw new IllegalArgumentException("not a SecretMap key: " + rawKey);
    }
    int split = rawKey.lastIndexOf(':');
    if (split < KEY_PREFIX.length() - 1) {
      throw new IllegalArgumentException("malformed SecretMap key: " + rawKey);
    }
    return split;
  }
}
