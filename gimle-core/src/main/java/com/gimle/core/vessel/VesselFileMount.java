package com.gimle.core.vessel;

import java.util.Optional;

/**
 * One {@code vessel.files} entry: render a tenant-scoped value's raw content to {@code path}
 * (relative to the instance's own data root, or absolute) before the process starts. The value
 * comes from exactly one of two sources: a plain {@code ConfigEntry} ({@code configKey}) or a
 * Fafnir secret ({@code secretKey}) -- the secret shape is the Kubernetes secret-volume-mount
 * analogue, written with owner-only file permissions since its content is, by declaration,
 * sensitive. No templating either way -- the value's bytes land on disk verbatim, the same "config
 * is opaque content" posture {@code ctx.config()} already has for module hosting.
 */
public record VesselFileMount(String path, Optional<String> configKey, Optional<String> secretKey) {

  public VesselFileMount {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("file mount path must not be blank");
    }
    if (configKey == null || secretKey == null) {
      throw new IllegalArgumentException("configKey/secretKey must not be null (use Optional)");
    }
    if (configKey.isPresent() == secretKey.isPresent()) {
      throw new IllegalArgumentException(
          "a file mount names exactly one source: a config key or a secret key");
    }
    if (configKey.filter(String::isBlank).isPresent()
        || secretKey.filter(String::isBlank).isPresent()) {
      throw new IllegalArgumentException("file mount source key must not be blank");
    }
  }

  /** Convenience: a plain-config-backed mount. */
  public VesselFileMount(String path, String configKey) {
    this(path, Optional.of(configKey), Optional.empty());
  }
}
