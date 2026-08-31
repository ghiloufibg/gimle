package com.gimle.cli;

import com.gimle.core.protocol.Json;
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
 *
 * <p>{@code -f -} reads the manifest from stdin instead of a file, kubectl's own convention.
 * Because {@link GimleCli#handleApply} peeks at {@code kind:} and then each resource-specific
 * command's own {@code apply} independently re-reads the manifest for its own {@code name:}
 * extraction and PUT body, a stdin manifest would otherwise be read (and drained) two or three
 * times over the same process's single invocation -- {@link #readManifestBytes} memoizes stdin's
 * bytes the first time they're read so every later call within the same process sees the same
 * content instead of an empty second read.
 */
final class ManifestFiles {

  private static final String STDIN = "-";

  private static byte[] cachedStdinBytes;

  private ManifestFiles() {}

  private static boolean isStdin(Path file) {
    return STDIN.equals(file.toString());
  }

  /**
   * Clears the memoized stdin read -- called once at the very start of each {@code apply}
   * invocation ({@link GimleCli#handleApply}), so a later, unrelated {@code apply -f -} call (e.g.
   * a second invocation within the same test JVM) reads its own fresh stdin rather than silently
   * reusing bytes cached by an earlier one.
   */
  static void resetStdinCache() {
    cachedStdinBytes = null;
  }

  /**
   * A second {@code -f}/{@code --file} is rejected outright rather than silently discarded the way
   * the original forward-scan-and-return-the-first-match implementation did -- {@code apply -f
   * a.yaml -f b.yaml} used to apply only {@code a.yaml}, with {@code b.yaml} never read at all and
   * no warning that it was ignored. {@code apply} is single-manifest by design (see this class's
   * own javadoc); a caller with more than one manifest to apply calls {@code gimle apply -f} once
   * per file.
   */
  static Path requireFileFlag(List<String> args) {
    Path found = null;
    for (int i = 0; i < args.size(); i++) {
      if ("-f".equals(args.get(i)) || "--file".equals(args.get(i))) {
        if (i + 1 >= args.size()) {
          throw new CliException(args.get(i) + " requires a value");
        }
        if (found != null) {
          throw new CliException(
              "apply accepts exactly one -f/--file per invocation -- apply each manifest with its"
                  + " own 'gimle apply -f' call");
        }
        found = Path.of(args.get(i + 1));
      }
    }
    if (found == null) {
      throw new CliException("apply requires -f <manifest.yaml> (or -f - to read from stdin)");
    }
    return found;
  }

  static byte[] readManifestBytes(Path file) {
    if (isStdin(file)) {
      if (cachedStdinBytes == null) {
        try {
          cachedStdinBytes = System.in.readAllBytes();
        } catch (IOException e) {
          throw new CliException("could not read manifest from stdin: " + e.getMessage(), e);
        }
      }
      return cachedStdinBytes;
    }
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
   * Parses the whole manifest root as a {@code Map}, for the kinds whose {@code apply} builds a
   * JSON body from manifest fields client-side (Service/NetworkPolicy/Tenant/LimitRange/Role/
   * RoleBinding/Account) rather than PUTting the YAML bytes verbatim the way the workload kinds do
   * -- those kinds' own server-side routes are JSON-only (see each command's own {@code set}).
   */
  static Map<String, Object> parseRoot(Path file, byte[] manifestBytes) {
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Object parsed;
    try {
      parsed = yaml.load(new ByteArrayInputStream(manifestBytes));
    } catch (RuntimeException e) {
      throw new CliException("malformed manifest " + file + ": " + e.getMessage(), e);
    }
    if (!(parsed instanceof Map<?, ?>)) {
      throw new CliException("manifest " + file + " has no top-level mapping");
    }
    return Json.asObject(parsed);
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
