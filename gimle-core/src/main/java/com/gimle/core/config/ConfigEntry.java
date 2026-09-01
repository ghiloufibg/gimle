package com.gimle.core.config;

/**
 * One tenant-scoped configuration or secret value. A single resource kind rather than two ({@code
 * Secret} vs a {@code ConfigMap} equivalent) -- {@code encrypted} is the only real difference,
 * matching the same reasoning that folded {@code AutoscalePolicy} into {@code DeploymentSpec}
 * rather than inventing a second top-level resource for one field's worth of difference. {@code
 * value} is ciphertext when {@code encrypted} is {@code true} -- decrypted only at the
 * control-plane leader, which alone holds the key file; it is stored and replicated as ciphertext
 * everywhere else, including this record when it travels between control-plane components that
 * never need the plaintext.
 *
 * <p>The same layering trick Fafnir's own {@code key@meta}/{@code key@N} secret-versioning
 * convention uses is reused a second time here: a named ConfigMap (a bundle of keys with its own
 * version, RBAC'd under {@code ResourceKind.CONFIGMAP}) is stored as one row keyed {@code
 * "configmap:" + name}, {@code encrypted=false}, its value a small JSON envelope of {@code
 * {version, data}} -- not a second store schema, just another synthetic-key convention only {@code
 * com.gimle.controlplane.configmap.ConfigMapCodec} ever interprets.
 */
public record ConfigEntry(String tenantId, String key, byte[] value, boolean encrypted) {

  /**
   * The largest a single stored value may be. Every row here is replicated through Raft consensus,
   * held whole in every store replica's own in-memory state, and written into every snapshot, so an
   * unbounded entry is a cluster-wide memory and replication cost rather than one process's
   * problem. 1 MiB is the same ceiling Kubernetes places on a Secret/ConfigMap for the same reason.
   * A caller writing plaintext this class only ever sees after encryption (see {@code
   * com.gimle.fafnir.SecretStore}) caps its own input lower, so the ciphertext's framing overhead
   * still fits inside this.
   */
  public static final int MAX_VALUE_BYTES = 1024 * 1024;

  public ConfigEntry {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("tenantId must not be blank");
    }
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
    if (value == null) {
      throw new IllegalArgumentException("value must not be null");
    }
    if (value.length > MAX_VALUE_BYTES) {
      throw new IllegalArgumentException(
          "value for "
              + tenantId
              + "/"
              + key
              + " is "
              + value.length
              + " bytes, exceeding the maximum of "
              + MAX_VALUE_BYTES);
    }
    value = value.clone();
  }

  @Override
  public byte[] value() {
    return value.clone();
  }
}
