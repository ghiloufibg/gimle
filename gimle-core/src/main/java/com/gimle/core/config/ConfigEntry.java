package com.gimle.core.config;

/**
 * One tenant-scoped configuration or secret value (Phase 5 design §6.2). One resource kind, not two
 * ({@code Secret} vs a {@code ConfigMap} equivalent) -- {@code encrypted} is the only real
 * difference, matching the same reasoning that folded {@code AutoscalePolicy} into {@code
 * DeploymentSpec} rather than inventing a second top-level resource for one field's worth of
 * difference. {@code value} is ciphertext when {@code encrypted} is {@code true} -- decrypted only
 * at the control-plane leader, which alone holds the key file (§6.1); it is stored and replicated
 * as ciphertext everywhere else, including this record when it travels between control-plane
 * components that never need the plaintext.
 */
public record ConfigEntry(String tenantId, String key, byte[] value, boolean encrypted) {

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
    value = value.clone();
  }

  @Override
  public byte[] value() {
    return value.clone();
  }
}
