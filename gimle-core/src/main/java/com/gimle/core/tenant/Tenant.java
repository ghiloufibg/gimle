package com.gimle.core.tenant;

/**
 * A tenant's identity and resource quota. Named {@code Tenant} rather than "namespace" because this
 * codebase already uses "namespace" for two other, unrelated concepts (JPMS {@code ModuleLayer}
 * namespacing, Linux namespace isolation); reusing the word here would invite exactly the ambiguity
 * this project's naming conventions elsewhere try to avoid.
 */
public record Tenant(String id, ResourceQuota quota) {

  public Tenant {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("tenant id must not be blank");
    }
    if (quota == null) {
      throw new IllegalArgumentException("quota must not be null");
    }
  }
}
