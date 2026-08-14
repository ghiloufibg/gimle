package com.gimle.holmgang.topology;

import com.gimle.core.exception.GimleManifestException;

/** A tenant provisioned right after boot, before any scenario runs. */
public record TenantSeed(String id, QuotaSpec quota) {

  public TenantSeed {
    if (id == null || id.isBlank()) {
      throw new GimleManifestException("seed tenant id must be a non-blank string");
    }
    if (quota == null) {
      throw new GimleManifestException("seed tenant " + id + " must declare a quota");
    }
  }
}
