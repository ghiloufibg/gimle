package com.gimle.hilmir.analyze;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The structural facts {@link JarStructureInspector} reads straight off a jar's entry list and
 * manifest -- no bytecode parsing, no descriptor parsing, mirroring the same {@code JarFile}/{@code
 * JarEntry} shape {@code ModuleArtifactReader} uses to decide "does this jar have {@code
 * module-info.class}, does it have {@code META-INF/gimle/gimle-module.yaml}."
 */
public record JarStructure(
    Path jarPath,
    ArtifactPackaging packaging,
    boolean hasModuleInfo,
    boolean hasGimleDescriptor,
    boolean launcherArchiveShaped,
    List<String> topLevelClassEntries,
    List<String> nativeLibraryEntries,
    List<String> loggingBindingHits,
    Optional<String> manifestImplementationVersion,
    Optional<String> manifestMainClass) {

  public JarStructure {
    topLevelClassEntries = List.copyOf(topLevelClassEntries);
    nativeLibraryEntries = List.copyOf(nativeLibraryEntries);
    loggingBindingHits = List.copyOf(loggingBindingHits);
  }
}
