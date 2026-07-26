package com.gimle.core.module;

public record Requirement(String moduleName, VersionRange range) {

  public Requirement {
    if (moduleName == null || moduleName.isBlank()) {
      throw new IllegalArgumentException("module name must not be blank");
    }
    if (range == null) {
      throw new IllegalArgumentException("version range must not be null");
    }
  }
}
