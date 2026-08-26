package com.gimle.cli;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Shared manifest-file handling for every {@code apply -f}-accepting command: locating the {@code
 * -f}/{@code --file} flag, reading the manifest's raw bytes, and parsing just enough YAML (via
 * SnakeYAML, the same library the control plane itself uses to parse it) to pull out a single
 * top-level field -- {@code name} for the six resource-specific commands, {@code kind} for {@link
 * GimleCli}'s own apply-dispatch -- without ever re-serializing the file, so the original bytes can
 * still be PUT verbatim and comments/formatting survive.
 */
final class ManifestFiles {

  private ManifestFiles() {}

  static Path requireFileFlag(List<String> args) {
    for (int i = 0; i < args.size(); i++) {
      if (("-f".equals(args.get(i)) || "--file".equals(args.get(i))) && i + 1 < args.size()) {
        return Path.of(args.get(i + 1));
      }
    }
    throw new CliException("apply requires -f <manifest.yaml>");
  }

  static byte[] readManifestBytes(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (NoSuchFileException e) {
      throw new CliException("could not read manifest file " + file + ": no such file", e);
    } catch (AccessDeniedException e) {
      throw new CliException("could not read manifest file " + file + ": permission denied", e);
    } catch (IOException e) {
      // Files.readAllBytes throws a plain IOException (not one of the two specific subtypes
      // above) for a directory -- distinguish that one other common case too rather than falling
      // back to the exception's own message, which for NoSuchFileException/AccessDeniedException
      // is just the path repeated with no stated reason at all.
      String reason = Files.isDirectory(file) ? "is a directory" : e.getMessage();
      throw new CliException("could not read manifest file " + file + ": " + reason, e);
    }
  }

  static String extractName(Path file, byte[] manifestBytes) {
    return extractField(file, manifestBytes, "name");
  }

  static String extractKind(Path file) {
    return extractField(file, readManifestBytes(file), "kind");
  }

  /**
   * Prints each {@code X-Gimle-Warning} the control plane attached to an apply response, one {@code
   * warning:} line per header, on stderr -- stdout's own {@code -o json}/table output stays
   * untouched, so scripts piping stdout never see these.
   */
  static void printWarnings(ApiResponse response, PrintStream err) {
    for (String warning : response.warnings()) {
      err.println("warning: " + warning);
    }
  }

  private static String extractField(Path file, byte[] manifestBytes, String field) {
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Object parsed;
    try {
      parsed = yaml.load(new ByteArrayInputStream(manifestBytes));
    } catch (RuntimeException e) {
      throw new CliException("malformed manifest " + file + ": " + e.getMessage(), e);
    }
    if (!(parsed instanceof Map<?, ?> map)
        || !(map.get(field) instanceof String value)
        || value.isBlank()) {
      throw new CliException("manifest " + file + " has no top-level '" + field + "' field");
    }
    return value;
  }
}
