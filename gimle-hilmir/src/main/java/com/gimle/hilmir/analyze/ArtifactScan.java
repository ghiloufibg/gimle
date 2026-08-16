package com.gimle.hilmir.analyze;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything {@code doctor}/{@code init} learned about one jar: its packaging shape, its structural
 * facts (present only for {@link ArtifactPackaging#PLAIN_JAR} -- nothing else can be opened as a
 * jar), any {@code gimle-module.yaml} it carries, and one {@link ClassHazards} per top-level class
 * entry. The single object both verbs consume so the jar/bytecode-inspection work happens exactly
 * once per artifact.
 */
public record ArtifactScan(
    Path jarPath,
    ArtifactPackaging packaging,
    Optional<JarStructure> structure,
    DescriptorSnapshot descriptor,
    Map<String, ClassHazards> classHazards,
    Set<String> topLevelPackages) {

  public ArtifactScan {
    classHazards = Map.copyOf(classHazards);
    topLevelPackages = Set.copyOf(topLevelPackages);
  }
}
