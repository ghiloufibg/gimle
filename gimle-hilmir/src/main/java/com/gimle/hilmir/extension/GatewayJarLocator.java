package com.gimle.hilmir.extension;

import com.gimle.core.module.Version;
import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.analyze.ArtifactScan;
import com.gimle.hilmir.analyze.ArtifactScanner;
import com.gimle.hilmir.analyze.DescriptorSnapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Resolves where {@code gimle-gateway}'s own jar lives on disk and derives its push coordinate --
 * the one genuinely new piece of "where am I installed" logic {@code hilmir enable gateway} needs.
 *
 * <p>Modules-directory resolution has exactly two supported sources, deliberately no third silent
 * fallback: an explicit {@code --modules-dir} flag, or (when that's absent) {@code
 * <GIMLE_HOME>/modules}, where {@code GIMLE_HOME} is the install root the {@code bin/hilmir}
 * wrapper script exports before launching. Guessing from {@code java.class.path} was considered and
 * rejected as too implicit -- a jar sitting on the launch classpath says nothing reliable about
 * where a platform archive's own {@code modules/} directory is.
 */
final class GatewayJarLocator {

  private static final String JAR_PREFIX = "gimle-gateway-";
  private static final String JAR_SUFFIX = ".jar";

  private GatewayJarLocator() {}

  record Coordinate(String name, Version version) {}

  static Path resolveModulesDir(ExtensionFlags flags) {
    return resolveModulesDir(flags, System.getenv("GIMLE_HOME"));
  }

  /**
   * Split out from {@link #resolveModulesDir(ExtensionFlags)} so a test can exercise both the
   * present- and absent-{@code GIMLE_HOME} cases directly, rather than needing to mutate the real
   * process environment (which the JDK makes deliberately awkward) just to simulate the absent
   * case.
   */
  static Path resolveModulesDir(ExtensionFlags flags, String gimleHomeEnv) {
    String override = flags.getOrDefault("--modules-dir", null);
    if (override != null) {
      return Path.of(override);
    }
    if (gimleHomeEnv == null || gimleHomeEnv.isBlank()) {
      throw new HilmirException(
          "cannot resolve a modules directory: GIMLE_HOME is not set and --modules-dir was not"
              + " given. GIMLE_HOME is exported automatically by the platform archive's own"
              + " bin/hilmir wrapper script; if hilmir was launched directly (a hand-built"
              + " classpath, or the standalone gimle-hilmir archive, which has no modules/ of its"
              + " own), pass --modules-dir <dir> explicitly instead.");
    }
    return Path.of(gimleHomeEnv).resolve("modules");
  }

  static Path findGatewayJar(Path modulesDir) {
    if (!Files.isDirectory(modulesDir)) {
      throw new HilmirException("modules directory not found: " + modulesDir);
    }
    List<Path> matches;
    try (Stream<Path> entries = Files.list(modulesDir)) {
      matches = entries.filter(GatewayJarLocator::isGatewayJar).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(
          "failed listing modules directory " + modulesDir + ": " + e.getMessage(), e);
    }
    if (matches.isEmpty()) {
      throw new HilmirException(
          "no " + JAR_PREFIX + "*" + JAR_SUFFIX + " found under " + modulesDir);
    }
    if (matches.size() > 1) {
      throw new HilmirException(
          "multiple "
              + JAR_PREFIX
              + "*"
              + JAR_SUFFIX
              + " files found under "
              + modulesDir
              + ": "
              + matches
              + " -- expected exactly one");
    }
    return matches.get(0);
  }

  // Files.list only ever yields modulesDir's own children, each of which always has a real file
  // name -- getFileName() is null only for a root path -- but SpotBugs can't see that from the
  // call site alone, so this guards explicitly rather than asserting it away.
  private static boolean isGatewayJar(Path path) {
    Path fileName = path.getFileName();
    return fileName != null && isGatewayJarName(fileName.toString());
  }

  private static boolean isGatewayJarName(String fileName) {
    return fileName.startsWith(JAR_PREFIX) && fileName.endsWith(JAR_SUFFIX);
  }

  static Coordinate deriveCoordinate(Path jarPath) {
    ArtifactScan scan = ArtifactScanner.scan(jarPath);
    DescriptorSnapshot descriptor = scan.descriptor();
    String name =
        descriptor
            .name()
            .orElseThrow(
                () ->
                    new HilmirException(
                        "gimle-gateway jar at "
                            + jarPath
                            + " has no 'name' in its own gimle-module.yaml -- its manifest looks"
                            + " broken"));
    Version version =
        descriptor
            .version()
            .orElseThrow(
                () ->
                    new HilmirException(
                        "gimle-gateway jar at "
                            + jarPath
                            + " has no 'version' in its own gimle-module.yaml -- its manifest"
                            + " looks broken"));
    return new Coordinate(name, version);
  }
}
