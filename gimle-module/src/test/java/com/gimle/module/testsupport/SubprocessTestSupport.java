package com.gimle.module.testsupport;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Shared "locate the {@code java} launcher and build a subprocess classpath from anchor classes"
 * plumbing for tests that spawn a real child JVM (the leak-detection tests) -- previously
 * duplicated near-verbatim between {@code RedeployLoopFlatMetaspaceTest} and {@code
 * RetainingPathAttributionTest}, differing only in which driver class each adds to the classpath.
 */
public final class SubprocessTestSupport {

  private SubprocessTestSupport() {}

  /**
   * The path to the {@code java} launcher that started this JVM — asked of the runtime itself
   * ({@link ProcessHandle}) rather than guessed from {@code os.name}, so there's no platform-name
   * string matching to get wrong or to need updating for a platform the check didn't anticipate.
   * Falls back to probing both conventional launcher filenames against the filesystem (still no OS
   * check — just "which of these files exists") for the rare environment where {@link
   * ProcessHandle.Info#command()} isn't populated.
   */
  public static String javaExecutable() {
    Optional<String> command = ProcessHandle.current().info().command();
    if (command.isPresent()) {
      return command.get();
    }
    Path javaBin = Path.of(System.getProperty("java.home"), "bin");
    for (String candidate : List.of("java", "java.exe")) {
      Path path = javaBin.resolve(candidate);
      if (Files.isRegularFile(path)) {
        return path.toString();
      }
    }
    throw new IllegalStateException("could not locate the java launcher under " + javaBin);
  }

  /**
   * Builds a classpath string from each anchor class's own code source location, in the order given
   * -- the caller supplies whichever driver/support classes its subprocess needs beyond the common
   * set every leak-detection subprocess needs (see {@code ModuleId}/{@code ModuleController}/{@code
   * Yaml}/{@code Logger} at each existing call site).
   */
  public static String buildClasspath(List<Class<?>> anchors) throws IOException {
    StringBuilder cp = new StringBuilder();
    for (Class<?> anchor : anchors) {
      if (cp.length() > 0) {
        cp.append(File.pathSeparator);
      }
      cp.append(modulePathEntryOf(anchor).toAbsolutePath());
    }
    return cp.toString();
  }

  public static Path modulePathEntryOf(Class<?> anchor) {
    try {
      return Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
