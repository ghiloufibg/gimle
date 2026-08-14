package com.gimle.controlplane.andvari;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
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
    if (ArtifactReference.isLocalPath(artifactPath)) {
      return ModuleArtifactReader.read(Path.of(artifactPath));
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
    return ModuleArtifactReader.read(andvariClient.pullThrough(cache, moduleId));
  }

  /** {@link #resolve} with every failure collapsed to empty -- admission's tolerant read. */
  public Optional<ModuleArtifact> resolveIfPossible(String artifactPath, ModuleId moduleId) {
    try {
      return Optional.of(resolve(artifactPath, moduleId));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
