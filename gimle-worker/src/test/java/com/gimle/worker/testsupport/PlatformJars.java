package com.gimle.worker.testsupport;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The module-path entries a fixture module built by a test has to compile against to implement the
 * platform's own hook/probe interfaces: the platform's own modules plus the third-party jars they
 * expose on their APIs.
 */
public final class PlatformJars {

  private PlatformJars() {}

  public static List<Path> onTestClasspath() {
    String[] jarNeedles = {"slf4j-api-", "logback-classic-", "logback-core-", "snakeyaml-"};
    // gimle-module/gimle-core resolve as installed jars when a test runs in isolation, but as
    // exploded target/classes directories when the reactor builds gimle-module and gimle-worker
    // together (Maven then points sibling modules at each other's build output, not the local
    // repo) -- both are valid JPMS module-path entries, so match either shape.
    String[] moduleArtifacts = {"gimle-module", "gimle-core"};
    List<Path> result = new ArrayList<>();
    String cp = System.getProperty("java.class.path");
    for (String entry : cp.split(File.pathSeparator)) {
      String fileName = Path.of(entry).getFileName().toString();
      String normalized = entry.replace('\\', '/');
      for (String needle : jarNeedles) {
        if (fileName.startsWith(needle)
            && fileName.endsWith(".jar")
            && !fileName.contains("tests")) {
          result.add(Path.of(entry));
        }
      }
      for (String artifact : moduleArtifacts) {
        boolean installedJar = fileName.startsWith(artifact + "-") && fileName.endsWith(".jar");
        boolean reactorClasses =
            normalized.endsWith("/" + artifact + "/target/classes")
                || normalized.endsWith("/" + artifact + "/target/test-classes");
        if ((installedJar && !fileName.contains("tests")) || reactorClasses) {
          result.add(Path.of(entry));
        }
      }
    }
    return result;
  }
}
