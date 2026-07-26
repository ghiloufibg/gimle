package com.gimle.core.module;

import java.nio.file.Path;

/** A resolved-on-disk module JAR: its identity, its parsed descriptor, and a content hash. */
public record ModuleArtifact(
    ModuleId id, Path jarPath, ModuleDescriptor descriptor, String sha256) {

  public ModuleArtifact {
    if (id == null) {
      throw new IllegalArgumentException("module id must not be null");
    }
    if (jarPath == null) {
      throw new IllegalArgumentException("jar path must not be null");
    }
    if (descriptor == null) {
      throw new IllegalArgumentException("descriptor must not be null");
    }
    if (sha256 == null || sha256.length() != 64) {
      throw new IllegalArgumentException("sha256 must be a 64-character hex digest");
    }
    if (!id.equals(descriptor.id())) {
      throw new IllegalArgumentException(
          "artifact id " + id + " does not match descriptor id " + descriptor.id());
    }
  }
}
