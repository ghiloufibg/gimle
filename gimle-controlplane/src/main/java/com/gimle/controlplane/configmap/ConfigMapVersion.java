package com.gimle.controlplane.configmap;

import java.util.Map;

/**
 * One immutable snapshot from a ConfigMap's version ledger (see {@link ConfigMapStore}). {@code
 * data} is empty exactly when {@code deleted} is {@code true} -- a tombstone records that the
 * ConfigMap had no live content as of this version, not what it used to hold.
 */
public record ConfigMapVersion(int version, Map<String, String> data, boolean deleted) {

  public ConfigMapVersion {
    data = Map.copyOf(data);
  }
}
