package com.gimle.holmgang.surtr;

import com.gimle.holmgang.HolmgangException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Resolves a committed example module jar by artifact id -- the built artifact Surtr deploys under
 * many names as its "pause image". One jar, N deployments: object count scales without building N
 * artifacts.
 */
final class ReferenceModule {

  private static final String GIMLE_VERSION = "0.1.0-SNAPSHOT";

  private static final Map<String, String> MODULE_NAMES =
      Map.of("greeter-provider", "com.gimle.examples.greeter.provider");

  private ReferenceModule() {}

  static String moduleName(final String artifact) {
    final String moduleName = MODULE_NAMES.get(artifact);
    if (moduleName == null) {
      throw new HolmgangException(
          "unknown reference module: "
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
