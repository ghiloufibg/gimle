package com.gimle.core.module;

/** A module's identity: its name paired with its version. */
public record ModuleId(String name, Version version) {

  public ModuleId {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("module name must not be blank");
    }
    if (version == null) {
      throw new IllegalArgumentException("version must not be null");
    }
  }

  @Override
  public String toString() {
    return name + "@" + version;
  }
}
