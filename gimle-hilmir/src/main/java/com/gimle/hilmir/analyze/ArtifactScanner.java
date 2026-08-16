package com.gimle.hilmir.analyze;

import com.gimle.core.exception.GimleManifestException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Orchestrates one artifact's full analysis: classify packaging, and for a plain jar, read its
 * structure, its optional {@code gimle-module.yaml}, and scan every top-level class entry's own
 * bytecode for hazards -- the single entry point {@code doctor} and {@code init} both call so a jar
 * is only ever opened and walked once.
 */
public final class ArtifactScanner {

  private static final String DESCRIPTOR_ENTRY = "META-INF/gimle/gimle-module.yaml";

  private ArtifactScanner() {}

  public static ArtifactScan scan(Path jarPath) {
    if (!Files.exists(jarPath)) {
      throw new GimleManifestException("artifact not found: " + jarPath);
    }
    ArtifactPackaging packaging = JarStructureInspector.classifyPackaging(jarPath);
    if (packaging != ArtifactPackaging.PLAIN_JAR) {
      return new ArtifactScan(
          jarPath,
          packaging,
          java.util.Optional.empty(),
          DescriptorSnapshot.ABSENT,
          Map.of(),
          Set.of());
    }

    JarStructure structure = JarStructureInspector.inspect(jarPath);
    DescriptorSnapshot descriptor =
        structure.hasGimleDescriptor() ? readDescriptor(jarPath) : DescriptorSnapshot.ABSENT;
    Map<String, ClassHazards> hazards = scanHazards(jarPath, structure);
    Set<String> packages = derivePackages(structure);

    return new ArtifactScan(
        jarPath, packaging, java.util.Optional.of(structure), descriptor, hazards, packages);
  }

  private static DescriptorSnapshot readDescriptor(Path jarPath) {
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      JarEntry entry = jar.getJarEntry(DESCRIPTOR_ENTRY);
      try (InputStream in = jar.getInputStream(entry)) {
        return DescriptorReader.read(in);
      }
    } catch (IOException e) {
      throw new GimleManifestException("failed to read gimle-module.yaml in " + jarPath, e);
    }
  }

  private static Map<String, ClassHazards> scanHazards(Path jarPath, JarStructure structure) {
    Map<String, ClassHazards> result = new LinkedHashMap<>();
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      for (String className : structure.topLevelClassEntries()) {
        String entryName = className.replace('.', '/') + ".class";
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) {
          continue;
        }
        byte[] bytes;
        try (InputStream in = jar.getInputStream(entry)) {
          bytes = in.readAllBytes();
        }
        try {
          result.put(className, BytecodeScanner.scan(bytes));
        } catch (RuntimeException e) {
          // A malformed/unusual .class entry (e.g. a multi-release-jar variant this scanner
          // doesn't specially handle) is skipped rather than aborting the whole scan -- one
          // unreadable class shouldn't hide every other finding.
        }
      }
    } catch (IOException e) {
      throw new GimleManifestException("failed to scan bytecode in " + jarPath, e);
    }
    return result;
  }

  private static Set<String> derivePackages(JarStructure structure) {
    Set<String> packages = new LinkedHashSet<>();
    for (String className : structure.topLevelClassEntries()) {
      int lastDot = className.lastIndexOf('.');
      if (lastDot > 0) {
        packages.add(className.substring(0, lastDot));
      }
    }
    return packages;
  }
}
