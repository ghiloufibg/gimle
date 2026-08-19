package com.gimle.holmgang.steps;

import com.gimle.holmgang.HolmgangException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Resolves the committed example modules feature files name by their artifact id. */
final class ExampleModules {

  private static final String GIMLE_VERSION = "0.1.0-alpha.2";

  private static final Map<String, String> MODULE_NAMES =
      Map.of(
          "greeter-provider", "com.gimle.examples.greeter.provider",
          "greeter-consumer", "com.gimle.examples.greeter.consumer",
          "greeter-load-generator", "com.gimle.examples.greeter.loadgen");

  private ExampleModules() {}

  static String moduleName(final String artifact) {
    final String moduleName = MODULE_NAMES.get(artifact);
    if (moduleName == null) {
      throw new HolmgangException(
          "unknown example module: "
              + artifact
              + " (expected one of "
              + MODULE_NAMES.keySet()
              + ")");
    }
    return moduleName;
  }

  static Path jar(final String artifact) {
    moduleName(artifact);
    final Path jar =
        Path.of("")
            .toAbsolutePath()
            .getParent()
            .resolve("gimle-examples")
            .resolve(artifact)
            .resolve("target")
            .resolve(artifact + "-" + GIMLE_VERSION + ".jar");
    if (!Files.isRegularFile(jar)) {
      throw new HolmgangException(
          "expected a built jar at " + jar + " -- run `mvn install` before -Pvalidation");
    }
    return jar;
  }
}
