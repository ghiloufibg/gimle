package com.gimle.module.artifactset;

import com.gimle.core.exception.GimleManifestException;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses an {@code ArtifactSet} manifest's {@code tenant:}/{@code modules:} grouping into a single
 * flattened, ordered {@link ArtifactSetManifest}. Deliberately still ignorant of the document's own
 * {@code kind:} field -- the same contract every sibling manifest parser in this codebase already
 * has, since the caller (a CLI's {@code kind:}-dispatch, or a Maven goal that only ever generates
 * one of these itself) already knows it's holding an {@code ArtifactSet} document before this class
 * ever sees it.
 *
 * <p>Every {@code artifact} path is resolved relative to the manifest file's own directory, never
 * hand-typed as a coordinate -- the actual {@code moduleId}/{@code version} for each entry is left
 * to a caller reading the jar itself (see {@code ModuleArtifactReader}), the same layering {@code
 * ModuleDescriptorParser} keeps between "what does the file say" and "what does the jar actually
 * contain."
 */
public final class ArtifactSetManifestParser {

  private ArtifactSetManifestParser() {}

  public static ArtifactSetManifest parse(Path manifestFile, byte[] manifestBytes) {
    Object raw;
    try {
      // SafeConstructor restricts loading to plain maps/lists/scalars, the same untrusted-input
      // posture ModuleDescriptorParser and ManifestFiles already take.
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      raw = yaml.load(new ByteArrayInputStream(manifestBytes));
    } catch (RuntimeException e) {
      throw new GimleManifestException(
          "malformed YAML in " + manifestFile + ": " + e.getMessage(), e);
    }
    if (!(raw instanceof Map<?, ?> root)) {
      throw new GimleManifestException(manifestFile + " must contain a YAML mapping at the root");
    }
    Path manifestDir = Optional.ofNullable(manifestFile.getParent()).orElse(Path.of(""));

    List<ArtifactSetModuleEntry> flattened = new ArrayList<>();
    Set<Path> seenPaths = new LinkedHashSet<>();
    parseTenants(root, manifestDir, flattened, seenPaths);
    parseUntenantedModules(root, manifestDir, flattened, seenPaths);

    if (flattened.isEmpty()) {
      throw new GimleManifestException(
          manifestFile
              + " names no modules -- expected a non-empty 'tenant' and/or 'modules'"
              + " section");
    }
    return new ArtifactSetManifest(List.copyOf(flattened));
  }

  /**
   * {@code tenant:} is a map of tenant id -> list of artifact paths, iterated in the map's own
   * (SnakeYAML-preserved, insertion-ordered) key order, each tenant's own list in the order written
   * -- exactly the push order {@link ArtifactSetManifest}'s own javadoc promises.
   */
  private static void parseTenants(
      Map<?, ?> root,
      Path manifestDir,
      List<ArtifactSetModuleEntry> flattened,
      Set<Path> seenPaths) {
    Object tenantObj = root.get("tenant");
    if (tenantObj == null) {
      return;
    }
    if (!(tenantObj instanceof Map<?, ?> tenants)) {
      throw new GimleManifestException("'tenant' must be a mapping of tenant id to artifact paths");
    }
    for (Map.Entry<?, ?> entry : tenants.entrySet()) {
      if (!(entry.getKey() instanceof String tenantId) || tenantId.isBlank()) {
        throw new GimleManifestException("each 'tenant' key must be a non-blank tenant id");
      }
      if (!(entry.getValue() instanceof List<?> paths)) {
        throw new GimleManifestException(
            "'tenant." + tenantId + "' must be a list of artifact paths");
      }
      for (Object pathValue : paths) {
        String rawPath = requireArtifactPath(pathValue, "tenant." + tenantId);
        addEntry(flattened, seenPaths, manifestDir, rawPath, Optional.of(tenantId));
      }
    }
  }

  /**
   * {@code modules:} is a plain list of untenanted artifact paths -- the exception, not the norm.
   */
  private static void parseUntenantedModules(
      Map<?, ?> root,
      Path manifestDir,
      List<ArtifactSetModuleEntry> flattened,
      Set<Path> seenPaths) {
    Object modulesObj = root.get("modules");
    if (modulesObj == null) {
      return;
    }
    if (!(modulesObj instanceof List<?> paths)) {
      throw new GimleManifestException("'modules' must be a list of artifact paths");
    }
    for (Object pathValue : paths) {
      String rawPath = requireArtifactPath(pathValue, "modules");
      addEntry(flattened, seenPaths, manifestDir, rawPath, Optional.empty());
    }
  }

  private static void addEntry(
      List<ArtifactSetModuleEntry> flattened,
      Set<Path> seenPaths,
      Path manifestDir,
      String rawPath,
      Optional<String> tenantId) {
    Path resolved = manifestDir.resolve(rawPath).normalize();
    if (!seenPaths.add(resolved)) {
      throw new GimleManifestException(
          "artifact path listed more than once (ownership must be unambiguous): " + rawPath);
    }
    flattened.add(new ArtifactSetModuleEntry(resolved, tenantId));
  }

  private static String requireArtifactPath(Object value, String section) {
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException(
          "each '" + section + "' entry must be a non-blank artifact path");
    }
    return s;
  }
}
