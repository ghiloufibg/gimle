package com.gimle.module.resolve;

import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.Requirement;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Synthetic {@link ModuleArtifact}s for resolver/registry tests that never touch a real jar. */
final class TestArtifacts {

  private TestArtifacts() {}

  static ModuleArtifact artifact(String name, String version, Requirement... requires) {
    ModuleDescriptor descriptor =
        new ModuleDescriptor(
            name,
            Version.parse(version),
            List.of(requires),
            List.of(),
            IsolationTier.TIER_1,
            new ResourceSpec("16Mi", "10m"),
            new ResourceSpec("32Mi", "50m"),
            HealthProbes.NONE,
            Optional.empty());
    return new ModuleArtifact(
        descriptor.id(), Path.of(name + "-" + version + ".jar"), descriptor, "0".repeat(64));
  }
}
