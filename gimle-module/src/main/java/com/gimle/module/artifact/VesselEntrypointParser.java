package com.gimle.module.artifact;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.vessel.VesselEntrypoint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses a bundle's {@code gimle-entrypoint.yaml} into a {@link VesselEntrypoint}. The bytes come
 * out of an artifact archive the registry deliberately never inspects, so this parser takes the
 * same untrusted-input posture every other YAML parser in this codebase does ({@code
 * SafeConstructor} -- plain maps/lists/scalars only) and every failure, structural or semantic, is
 * a {@link GimleManifestException} naming the file.
 */
public final class VesselEntrypointParser {

  private VesselEntrypointParser() {}

  /** Reads {@code {bundleRoot}/gimle-entrypoint.yaml}; the file must exist. */
  public static VesselEntrypoint parseFromBundleRoot(Path bundleRoot) {
    Path file = bundleRoot.resolve(VesselEntrypoint.FILE_NAME);
    if (!Files.isRegularFile(file)) {
      throw new GimleManifestException(
          "bundle has no " + VesselEntrypoint.FILE_NAME + " at its root: " + bundleRoot);
    }
    String content;
    try {
      content = Files.readString(file);
    } catch (IOException e) {
      throw new GimleManifestException("failed to read " + file + ": " + e.getMessage(), e);
    }
    return parse(content, file);
  }

  static VesselEntrypoint parse(String yamlContent, Path describedFile) {
    Object raw;
    try {
      raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlContent);
    } catch (RuntimeException e) {
      throw new GimleManifestException(
          "malformed YAML in " + describedFile + ": " + e.getMessage(), e);
    }
    if (!(raw instanceof Map<?, ?> root)) {
      throw new GimleManifestException(describedFile + " must contain a YAML mapping at the root");
    }
    List<String> command = parseCommand(root.get("command"), describedFile);
    String workdir = parseWorkdir(root.get("workdir"), describedFile);
    try {
      return new VesselEntrypoint(command, workdir);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException(describedFile + ": " + e.getMessage(), e);
    }
  }

  private static List<String> parseCommand(Object value, Path describedFile) {
    if (!(value instanceof List<?> entries) || entries.isEmpty()) {
      throw new GimleManifestException(
          describedFile + ": 'command' must be a non-empty list of strings");
    }
    List<String> command = new ArrayList<>();
    for (Object entry : entries) {
      if (!(entry instanceof String s) || s.isBlank()) {
        throw new GimleManifestException(
            describedFile + ": each 'command' entry must be a non-blank string");
      }
      command.add(s);
    }
    return command;
  }

  private static String parseWorkdir(Object value, Path describedFile) {
    if (value == null) {
      return VesselEntrypoint.DEFAULT_WORKDIR;
    }
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException(
          describedFile + ": 'workdir' must be a non-blank string when present");
    }
    return s;
  }
}
