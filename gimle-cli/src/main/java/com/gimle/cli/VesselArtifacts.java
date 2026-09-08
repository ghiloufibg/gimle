package com.gimle.cli;

import com.gimle.module.artifact.ModuleArtifactReader;
import java.nio.file.Path;

/**
 * Shared "is this actually a vessel jar" check for the two places an operator can claim {@code
 * --vessel}/{@code kind: vessel} for a jar: {@link ArtifactCommand#push} and {@link
 * ArtifactSetCommand#resolveVessel}. A jar carrying a real {@code gimle-module.yaml} descriptor
 * already has a trustworthy coordinate of its own -- honoring an operator-supplied {@code
 * --name}/{@code --version} for it instead would let the registry coordinate and the jar's actual
 * declared identity silently disagree, exactly what a coordinate-only deployment manifest's {@code
 * module: {name, version}} reference depends on never happening.
 */
final class VesselArtifacts {

  private VesselArtifacts() {}

  /**
   * Throws if {@code jar} is a real module artifact ({@code ModuleArtifactReader.read} succeeds). A
   * jar {@code read} can't make sense of -- the ordinary vessel shape -- is left alone.
   */
  static void rejectIfRealModule(Path jar) {
    boolean isRealModule;
    try {
      ModuleArtifactReader.read(jar);
      isRealModule = true;
    } catch (RuntimeException e) {
      isRealModule = false;
    }
    if (isRealModule) {
      throw new CliException(
          jar
              + " carries a real gimle-module.yaml module descriptor -- it is not a vessel jar."
              + " Drop --vessel/kind: vessel and push it normally so its coordinate is read from"
              + " the descriptor itself, not taken on faith from an operator-supplied"
              + " name/version");
    }
  }
}
