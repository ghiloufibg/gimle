package com.gimle.core.module;

import java.util.Optional;

public record HealthProbes(Optional<String> livenessClass, Optional<String> readinessClass) {

  public static final HealthProbes NONE = new HealthProbes(Optional.empty(), Optional.empty());

  public HealthProbes {
    if (livenessClass == null || readinessClass == null) {
      throw new IllegalArgumentException("probe fields must be Optional.empty(), not null");
    }
  }
}
