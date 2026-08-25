package com.gimle.controlplane.andvari;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.core.vessel.VesselArtifacts;
import com.gimle.core.vessel.VesselSpec;
import com.gimle.module.artifact.ArtifactPullCache;
import com.gimle.module.artifact.ModuleArtifactReader;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The control plane's one way of turning a workload spec's artifact reference into a readable
 * {@link ModuleArtifact}, shared by admission (which needs the descriptor for quota and the digest
 * to record) and by every reconciler (which needs the descriptor for scheduling): a local {@code
 * artifactPath} is read directly exactly as before, and a blank reference resolves the module
 * coordinate through Andvari into this process's own pull-through cache. Centralizing the branch
 * here keeps "which kind of reference is this" from being re-decided at five call sites.
 */
public final class ArtifactResolver {

  private final AndvariClient andvariClient;
  private final ArtifactPullCache cache;

  private ArtifactResolver(AndvariClient andvariClient, ArtifactPullCache cache) {
    this.andvariClient = andvariClient;
    this.cache = cache;
  }

  /**
   * A resolver with no registry behind it: local paths work exactly as always, and a
   * registry-coordinate reference fails with a message naming the missing configuration -- the
   * state of every control plane started without {@code --andvari-endpoint}.
   */
  public static ArtifactResolver localOnly() {
    return new ArtifactResolver(null, null);
  }

  public static ArtifactResolver withRegistry(
      AndvariClient andvariClient, ArtifactPullCache cache) {
    return new ArtifactResolver(andvariClient, cache);
  }

  /** The registry client, empty when this control plane has no {@code --andvari-endpoint}. */
  public Optional<AndvariClient> registryClient() {
    return Optional.ofNullable(andvariClient);
  }

  /**
   * Resolves and reads the artifact, throwing a {@link GimleManifestException} whose message names
   * what failed -- the same contract {@code ModuleArtifactReader.read} already has for a local
   * path, extended to coordinate resolution, so existing catch-and-log call sites keep working
   * unchanged.
   */
  public ModuleArtifact resolve(String artifactPath, ModuleId moduleId) {
    return resolve(artifactPath, moduleId, Optional.empty());
  }

  /**
   * {@code vessel}, when present, means {@code artifactPath}/{@code moduleId} name a plain runnable
   * artifact rather than a real Java module. A local path is still read and digested directly via
   * {@link VesselArtifacts#readVesselArtifact} (there is no registry to ask about a local file). A
   * registry coordinate, however, resolves <b>metadata-only</b>: nothing in this process ever uses
   * a vessel artifact's bytes -- the descriptor is synthesized entirely from the manifest's own
   * {@code vessel:} block, and the digest (what drift detection and admission actually consume)
   * comes straight from the registry's own {@code HEAD} answer -- so downloading the artifact here
   * would be pure waste, and for a bundle there isn't even a single file to hold. Only node agents
   * ever download (and, for a bundle, unpack) a vessel artifact. This is the one choke point that
   * keeps the scheduler, tenant quota accounting, and every reconciler's own artifact-drift check
   * working unchanged for a vessel-hosted spec without any of them needing to know it's a vessel --
   * they still just get a {@link ModuleArtifact} back.
   */
  public ModuleArtifact resolve(
      String artifactPath, ModuleId moduleId, Optional<VesselSpec> vessel) {
    if (ArtifactReference.isLocalPath(artifactPath)) {
      Path jarPath = Path.of(artifactPath);
      return vessel.isPresent()
          ? VesselArtifacts.readVesselArtifact(jarPath, moduleId, vessel.get())
          : ModuleArtifactReader.read(jarPath);
    }
    if (andvariClient == null) {
      throw new GimleManifestException(
          "spec resolves module "
              + moduleId.name()
              + ":"
              + moduleId.version()
              + " from the artifact registry, but this control plane has no --andvari-endpoint"
              + " configured");
    }
    if (vessel.isPresent()) {
      return resolveVesselMetadata(moduleId, vessel.get());
    }
    Path pulled = andvariClient.pullThrough(cache, moduleId);
    return ModuleArtifactReader.read(pulled);
  }

  /**
   * The metadata-only registry resolution for a vessel-hosted spec. The {@link
   * ModuleArtifact#jarPath} component is deliberately a placeholder: no consumer of a vessel's
   * {@code ModuleArtifact} in this process reads it (the bytes live only on the nodes that run
   * them), and giving it a real-looking value would only invite one to start.
   */
  private ModuleArtifact resolveVesselMetadata(ModuleId moduleId, VesselSpec vessel) {
    return switch (andvariClient.head(moduleId)) {
      case AndvariClient.HeadOutcome.Found found ->
          new ModuleArtifact(
              moduleId,
              Path.of(""),
              VesselArtifacts.syntheticDescriptor(moduleId, vessel),
              found.sha256());
      case AndvariClient.HeadOutcome.NotFound ignored ->
          throw new GimleManifestException(
              "artifact "
                  + moduleId.name()
                  + ":"
                  + moduleId.version()
                  + " is not in the artifact registry");
      case AndvariClient.HeadOutcome.Unreachable unreachable ->
          throw new GimleManifestException(
              "artifact registry unreachable while resolving "
                  + moduleId.name()
                  + ":"
                  + moduleId.version()
                  + ": "
                  + unreachable.reason());
    };
  }

  /** {@link #resolve} with every failure collapsed to empty -- admission's tolerant read. */
  public Optional<ModuleArtifact> resolveIfPossible(String artifactPath, ModuleId moduleId) {
    return resolveIfPossible(artifactPath, moduleId, Optional.empty());
  }

  /** {@link #resolve(String, ModuleId, Optional)} with every failure collapsed to empty. */
  public Optional<ModuleArtifact> resolveIfPossible(
      String artifactPath, ModuleId moduleId, Optional<VesselSpec> vessel) {
    try {
      return Optional.of(resolve(artifactPath, moduleId, vessel));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
