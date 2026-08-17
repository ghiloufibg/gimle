package com.gimle.core.vessel;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.hash.Sha256;
import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Turns a vessel's plain runnable jar into the same {@link ModuleArtifact} shape a real module
 * artifact resolves to, so every piece of the control plane that already speaks {@code
 * ModuleArtifact}/{@code ModuleDescriptor} -- the scheduler, tenant quota accounting -- keeps
 * working unchanged for a vessel-hosted workload without ever needing to know it's a vessel.
 * Deliberately does not use {@code ModuleArtifactReader}: that reader requires a {@code
 * module-info.class} and a bundled {@code gimle-module.yaml}, neither of which a plain runnable jar
 * has by definition.
 */
public final class VesselArtifacts {

  private VesselArtifacts() {}

  /**
   * A {@link ModuleDescriptor} synthesized from the manifest's own {@code vessel:} block rather
   * than read from the jar: always {@link IsolationTier#TIER_2} (a vessel is always its own
   * dedicated process), no exports/requires/hooks/volume (none of that concept applies to an opaque
   * external process), health probes always {@link HealthProbes#NONE} (a vessel's own probe ladder
   * is dialed externally by the agent, an entirely separate mechanism from {@code
   * ModuleDescriptor.healthProbes}).
   */
  public static ModuleDescriptor syntheticDescriptor(ModuleId id, VesselSpec vessel) {
    return new ModuleDescriptor(
        id.name(),
        id.version(),
        List.of(),
        List.of(),
        IsolationTier.TIER_2,
        vessel.resourceRequest(),
        vessel.resourceLimit(),
        HealthProbes.NONE,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  /**
   * Reads {@code jarPath} into a {@link ModuleArtifact} carrying {@link #syntheticDescriptor}'s
   * descriptor -- the jar's bytes are still digested for real (a vessel jar can be swapped out from
   * under a running deployment exactly as a module jar can, and the same drift check must be able
   * to catch it), just never opened as a JAR/parsed for a module descriptor.
   */
  public static ModuleArtifact readVesselArtifact(Path jarPath, ModuleId id, VesselSpec vessel) {
    if (!Files.isRegularFile(jarPath)) {
      throw new GimleManifestException("vessel artifact not found: " + jarPath);
    }
    String sha256;
    try {
      sha256 = sha256Of(jarPath);
    } catch (IOException e) {
      throw new GimleManifestException("failed to read vessel artifact: " + jarPath, e);
    }
    return new ModuleArtifact(id, jarPath, syntheticDescriptor(id, vessel), sha256);
  }

  private static String sha256Of(Path jarPath) throws IOException {
    return Sha256.sha256Hex(jarPath);
  }
}
