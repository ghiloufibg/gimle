package com.gimle.hilmir.analyze;

import com.gimle.core.exception.GimleManifestException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Reads a jar's own entry list and manifest into a {@link JarStructure} -- the structural
 * jar-inspection pattern {@code ModuleArtifactReader} already established (open with {@code
 * JarFile}, look up specific entries by name), extended here to the broader set of shape signals
 * {@code doctor}/{@code init} need: launcher-archive nesting, bundled native libraries, bundled
 * logging bindings, and the manifest's own {@code Implementation-Version}/{@code Main-Class}.
 */
public final class JarStructureInspector {

  private static final String MODULE_INFO_ENTRY = "module-info.class";
  private static final String DESCRIPTOR_ENTRY = "META-INF/gimle/gimle-module.yaml";

  // Nested-directory prefixes a launcher archive (Spring Boot fat jar, or any layout that mimics
  // it) always carries -- a plain flat jar never has entries under either of these.
  private static final List<String> NESTED_ARCHIVE_PREFIXES = List.of("BOOT-INF/", "WEB-INF/");

  private static final Set<String> KNOWN_LAUNCHER_MAIN_CLASSES =
      Set.of(
          "org.springframework.boot.loader.JarLauncher",
          "org.springframework.boot.loader.WarLauncher",
          "org.springframework.boot.loader.PropertiesLauncher",
          "org.springframework.boot.loader.launch.JarLauncher",
          "org.springframework.boot.loader.launch.WarLauncher",
          "org.springframework.boot.loader.launch.PropertiesLauncher",
          "io.quarkus.bootstrap.runner.QuarkusEntryPoint");

  // entry-path-prefix -> the bundled logging binding it signals, checked against every entry
  // (including the "<lib-dir>/<jarfile>.jar" entries of a nested fat-jar layout, matched by
  // filename substring rather than opened as a nested zip -- see this class's own javadoc on
  // depth).
  private static final List<String> LOGGING_BINDING_CLASS_PREFIXES =
      List.of("ch/qos/logback/classic/", "org/apache/logging/log4j/core/", "org/slf4j/simple/");
  private static final List<String> LOGGING_BINDING_JAR_NAME_HINTS =
      List.of("logback-classic", "log4j-core", "slf4j-simple");

  private static final Set<String> NATIVE_LIBRARY_SUFFIXES = Set.of(".so", ".dll", ".dylib");

  private JarStructureInspector() {}

  public static ArtifactPackaging classifyPackaging(Path path) {
    if (Files.isDirectory(path)) {
      return ArtifactPackaging.DIRECTORY;
    }
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(".war")) {
      return ArtifactPackaging.WAR;
    }
    if (name.endsWith(".ear")) {
      return ArtifactPackaging.EAR;
    }
    if (!looksLikeZip(path)) {
      return ArtifactPackaging.NOT_A_JAR;
    }
    return ArtifactPackaging.PLAIN_JAR;
  }

  private static boolean looksLikeZip(Path path) {
    try (var in = Files.newInputStream(path)) {
      byte[] header = in.readNBytes(4);
      // The ZIP local-file-header magic number "PK\x03\x04" -- every jar (a ZIP container) starts
      // with it; an empty or single-entry-less-than-4-byte archive is not a realistic module/vessel
      // artifact either way.
      return header.length == 4
          && header[0] == 0x50
          && header[1] == 0x4B
          && header[2] == 0x03
          && header[3] == 0x04;
    } catch (IOException e) {
      return false;
    }
  }

  /** Only meaningful for {@link ArtifactPackaging#PLAIN_JAR} -- callers must check that first. */
  public static JarStructure inspect(Path jarPath) {
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      boolean hasModuleInfo = jar.getJarEntry(MODULE_INFO_ENTRY) != null;
      boolean hasDescriptor = jar.getJarEntry(DESCRIPTOR_ENTRY) != null;

      boolean nestedArchiveShaped = false;
      List<String> topLevelClasses = new ArrayList<>();
      List<String> nativeLibraries = new ArrayList<>();
      Set<String> loggingBindingHits = new LinkedHashSet<>();

      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        if (isUnderAnyPrefix(name, NESTED_ARCHIVE_PREFIXES)) {
          nestedArchiveShaped = true;
        }
        if (entry.isDirectory()) {
          continue;
        }
        recordNativeLibrary(name, nativeLibraries);
        recordLoggingBindingHit(name, loggingBindingHits);
        if (name.endsWith(".class")
            && !name.equals(MODULE_INFO_ENTRY)
            && !isUnderAnyPrefix(name, NESTED_ARCHIVE_PREFIXES)
            && !name.contains("module-info")) {
          topLevelClasses.add(
              name.substring(0, name.length() - ".class".length()).replace('/', '.'));
        }
      }

      Manifest manifest = jar.getManifest();
      Optional<String> implementationVersion = Optional.empty();
      Optional<String> mainClass = Optional.empty();
      if (manifest != null) {
        implementationVersion =
            Optional.ofNullable(manifest.getMainAttributes().getValue("Implementation-Version"));
        mainClass = Optional.ofNullable(manifest.getMainAttributes().getValue("Main-Class"));
      }
      boolean launcherArchiveShaped =
          nestedArchiveShaped || mainClass.map(KNOWN_LAUNCHER_MAIN_CLASSES::contains).orElse(false);

      return new JarStructure(
          jarPath,
          ArtifactPackaging.PLAIN_JAR,
          hasModuleInfo,
          hasDescriptor,
          launcherArchiveShaped,
          topLevelClasses,
          nativeLibraries,
          List.copyOf(loggingBindingHits),
          implementationVersion,
          mainClass);
    } catch (IOException e) {
      throw new GimleManifestException("failed to read jar: " + jarPath, e);
    }
  }

  private static void recordNativeLibrary(String entryName, List<String> out) {
    String lower = entryName.toLowerCase(Locale.ROOT);
    for (String suffix : NATIVE_LIBRARY_SUFFIXES) {
      if (lower.endsWith(suffix)) {
        out.add(entryName);
        return;
      }
    }
  }

  private static void recordLoggingBindingHit(String entryName, Set<String> out) {
    for (String prefix : LOGGING_BINDING_CLASS_PREFIXES) {
      if (entryName.startsWith(prefix)) {
        out.add(prefix);
      }
    }
    if (entryName.endsWith(".jar") && entryName.contains("/lib/")) {
      for (String hint : LOGGING_BINDING_JAR_NAME_HINTS) {
        if (entryName.contains(hint)) {
          out.add(hint);
        }
      }
    }
  }

  private static boolean isUnderAnyPrefix(String name, List<String> prefixes) {
    for (String prefix : prefixes) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
