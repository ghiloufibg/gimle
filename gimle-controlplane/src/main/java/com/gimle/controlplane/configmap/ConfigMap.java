package com.gimle.controlplane.configmap;

import java.util.Map;

/**
 * A named, multi-key configuration bundle a Deployment attaches by reference ({@code
 * configMapRefs}) instead of receiving its tenant's entire flat config set. Stored as one {@link
 * com.gimle.core.config.ConfigEntry} row per {@code (tenantId, name)} -- see {@link ConfigMapCodec}
 * for the wire/storage envelope. {@code version} exists purely for optimistic- concurrency conflict
 * detection ({@link ConfigMapStore#put}/{@link ConfigMapStore#patch}); there is deliberately no
 * version history to roll back to, unlike Fafnir's own versioned secrets -- ConfigMap overwrites in
 * place.
 */
public record ConfigMap(String tenantId, String name, int version, Map<String, String> data) {

  public ConfigMap {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("tenantId must not be blank");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative: " + version);
    }
    if (data == null) {
      throw new IllegalArgumentException("data must not be null");
    }
    data = Map.copyOf(data);
  }
}
