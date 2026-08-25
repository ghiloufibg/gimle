package com.gimle.module.artifactset;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.manifest.ApiVersion;
import com.gimle.core.vessel.VesselEntrypoint;
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
 * ever sees it. The optional top-level {@code apiVersion:} is read (and an unsupported one
 * rejected) here, though: {@code v1} is a straight promotion of the alpha schema -- an {@code
 * artifact:} entry is a local build output being pushed <em>into</em> the registry, resolved
 * against this manifest file's own directory, so unlike the workload kinds there is nothing to
 * deprecate between the two versions and both parse identically.
 *
 * <p>A list item is either a bare string -- an ordinary module jar path, today's exact original
 * shape, whose real {@code moduleId}/{@code version} are left to a caller reading the jar itself
 * (see {@code ModuleArtifactReader}) -- or a mapping declaring an explicit entry {@code kind:}
 * ({@code vessel} or {@code bundle}, never defaulted: neither shape has a descriptor to read a
 * coordinate from, so the manifest must name {@code name}/{@code version} itself, and a silent
 * default kind could silently be the wrong one). Every {@code artifact} path is resolved relative
 * to the manifest file's own directory.
 */
public final class ArtifactSetManifestParser {

  private static final Set<ApiVersion> SUPPORTED_VERSIONS =
      Set.of(ApiVersion.V1ALPHA1, ApiVersion.V1);

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
    // Validation only: v1 and v1alpha1 parse identically for this kind (see the class javadoc),
    // but an unknown or malformed apiVersion still fails loudly instead of being ignored.
    ApiVersion.of(root, "ArtifactSet", SUPPORTED_VERSIONS);
    Path manifestDir = Optional.ofNullable(manifestFile.getParent()).orElse(Path.of(""));

    List<ArtifactSetEntry> flattened = new ArrayList<>();
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
   * {@code tenant:} is a map of tenant id -> list of entries, iterated in the map's own
   * (SnakeYAML-preserved, insertion-ordered) key order, each tenant's own list in the order written
   * -- exactly the push order {@link ArtifactSetManifest}'s own javadoc promises.
   */
  private static void parseTenants(
      Map<?, ?> root, Path manifestDir, List<ArtifactSetEntry> flattened, Set<Path> seenPaths) {
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
      if (!(entry.getValue() instanceof List<?> items)) {
        throw new GimleManifestException(
            "'tenant." + tenantId + "' must be a list of artifact entries");
      }
      for (Object item : items) {
        addEntry(
            flattened, seenPaths, manifestDir, item, Optional.of(tenantId), "tenant." + tenantId);
      }
    }
  }

  /** {@code modules:} is a plain list of untenanted entries -- the exception, not the norm. */
  private static void parseUntenantedModules(
      Map<?, ?> root, Path manifestDir, List<ArtifactSetEntry> flattened, Set<Path> seenPaths) {
    Object modulesObj = root.get("modules");
    if (modulesObj == null) {
      return;
    }
    if (!(modulesObj instanceof List<?> items)) {
      throw new GimleManifestException("'modules' must be a list of artifact entries");
    }
    for (Object item : items) {
      addEntry(flattened, seenPaths, manifestDir, item, Optional.empty(), "modules");
    }
  }

  private static void addEntry(
      List<ArtifactSetEntry> flattened,
      Set<Path> seenPaths,
      Path manifestDir,
      Object item,
      Optional<String> tenantId,
      String section) {
    ArtifactSetEntry entry;
    if (item instanceof String rawPath) {
      if (rawPath.isBlank()) {
        throw new GimleManifestException(
            "each '" + section + "' entry must be a non-blank artifact path or a mapping");
      }
      entry = new ArtifactSetEntry.Module(resolvePath(manifestDir, rawPath), tenantId);
    } else if (item instanceof Map<?, ?> mapping) {
      entry = parseMappingEntry(mapping, manifestDir, tenantId, section);
    } else {
      throw new GimleManifestException(
          "each '" + section + "' entry must be a non-blank artifact path or a mapping");
    }
    if (!seenPaths.add(entry.artifact())) {
      throw new GimleManifestException(
          "artifact path listed more than once (ownership must be unambiguous): "
              + entry.artifact());
    }
    flattened.add(entry);
  }

  private static ArtifactSetEntry parseMappingEntry(
      Map<?, ?> mapping, Path manifestDir, Optional<String> tenantId, String section) {
    Path artifact = resolvePath(manifestDir, requireString(mapping, "artifact", section));
    String kind = requireString(mapping, "kind", section);
    String name = requireString(mapping, "name", section);
    String version = requireString(mapping, "version", section);
    return switch (kind) {
      case "vessel" -> {
        if (mapping.containsKey("command") || mapping.containsKey("workdir")) {
          throw new GimleManifestException(
              "'"
                  + section
                  + "' vessel entry "
                  + name
                  + " must not declare command/workdir -- a single runnable jar has no ambiguity"
                  + " about what runs it");
        }
        yield new ArtifactSetEntry.Vessel(artifact, tenantId, name, version);
      }
      case "bundle" -> {
        List<String> command = requireStringList(mapping, "command", section);
        String workdir =
            mapping.get("workdir") == null ? null : String.valueOf(mapping.get("workdir"));
        VesselEntrypoint entrypoint;
        try {
          entrypoint = new VesselEntrypoint(command, workdir);
        } catch (IllegalArgumentException e) {
          throw new GimleManifestException(
              "'" + section + "' bundle entry " + name + ": " + e.getMessage(), e);
        }
        yield new ArtifactSetEntry.Bundle(artifact, tenantId, name, version, entrypoint);
      }
      default ->
          throw new GimleManifestException(
              "'"
                  + section
                  + "' entry declares unknown kind '"
                  + kind
                  + "' -- expected 'vessel' or 'bundle' (a plain module jar is a bare path, not a"
                  + " mapping)");
    };
  }

  private static Path resolvePath(Path manifestDir, String rawPath) {
    return manifestDir.resolve(rawPath).normalize();
  }

  private static String requireString(Map<?, ?> mapping, String key, String section) {
    Object value = mapping.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException(
          "each '" + section + "' mapping entry must declare a non-blank '" + key + "'");
    }
    return s;
  }

  private static List<String> requireStringList(Map<?, ?> mapping, String key, String section) {
    Object value = mapping.get(key);
    if (!(value instanceof List<?> items) || items.isEmpty()) {
      throw new GimleManifestException(
          "each '" + section + "' bundle entry must declare a non-empty '" + key + "' list");
    }
    List<String> strings = new ArrayList<>();
    for (Object item : items) {
      if (!(item instanceof String s) || s.isBlank()) {
        throw new GimleManifestException(
            "each '" + key + "' entry in '" + section + "' must be a non-blank string");
      }
      strings.add(s);
    }
    return strings;
  }
}
