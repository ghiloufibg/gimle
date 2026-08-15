package com.gimle.core.vessel;

/**
 * One {@code vessel.files} entry: render a tenant-scoped {@code ConfigEntry}'s raw content to
 * {@code path} (relative to the instance's own data root, or absolute) before the process starts.
 * No templating -- the config value's bytes land on disk verbatim, the same "config is opaque
 * content" posture {@code ctx.config()} already has for module hosting.
 */
public record VesselFileMount(String path, String configKey) {

  public VesselFileMount {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("file mount path must not be blank");
    }
    if (configKey == null || configKey.isBlank()) {
      throw new IllegalArgumentException("file mount config key must not be blank");
    }
  }
}
