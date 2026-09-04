package com.gimle.hugin.model;

import java.util.Optional;

/**
 * What identifies one instance across polls: the tenant-scoped triple every control-plane route
 * keyed on an instance already uses. Selection survives a refresh by matching on this rather than
 * on a row position, so a row appearing above the selected one doesn't silently move the cursor
 * onto a different instance.
 */
public record InstanceKey(Optional<String> tenantId, String deploymentName, int instanceIndex) {

  public InstanceKey {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must not be null; use Optional.empty()");
    }
    if (deploymentName == null || deploymentName.isBlank()) {
      throw new IllegalArgumentException("deploymentName must not be blank");
    }
    if (instanceIndex < 0) {
      throw new IllegalArgumentException("instanceIndex must not be negative: " + instanceIndex);
    }
  }

  @Override
  public String toString() {
    return deploymentName + "/" + instanceIndex;
  }
}
