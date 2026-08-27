package com.gimle.holmgang.surtr;

import com.gimle.holmgang.HolmgangException;
import com.gimle.ragnarok.surtr.ModuleJarSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The one {@link ModuleJarSource} {@code -Pvalidation} runs against: resolves a workload's {@code
 * module:} artifact id to the identical built example-module jar {@code SurtrIT} always deployed
 * before the engine itself moved to {@code gimle-ragnarok} -- same version, same repo-relative
 * path. Deliberately not part of {@code gimle-ragnarok} itself: the relative path this class
 * resolves (this repo's own {@code gimle-examples/} layout, one directory up from wherever the test
 * JVM's working directory happens to be) is specific to running from within a checkout of this
 * repo, not something a shipped tool run from an arbitrary working directory should assume.
 */
public final class ExampleModuleJarSource implements ModuleJarSource {

  private static final String GIMLE_VERSION = "0.1.0-alpha.2";

  private static final Map<String, String> MODULE_NAMES =
      Map.of("greeter-provider", "com.gimle.examples.greeter.provider");

  @Override
  public String moduleName(final String artifactId) {
    final String moduleName = MODULE_NAMES.get(artifactId);
    if (moduleName == null) {
      throw new HolmgangException(
          "unknown reference module: "
              + artifactId
              + " (expected one of "
              + MODULE_NAMES.keySet()
              + ")");
    }
    return moduleName;
  }

  @Override
  public Path jar(final String artifactId) {
    moduleName(artifactId);
    final Path jar =
        Path.of("")
            .toAbsolutePath()
            .getParent()
            .resolve("gimle-examples")
            .resolve(artifactId)
            .resolve("target")
            .resolve(artifactId + "-" + GIMLE_VERSION + ".jar");
    if (!Files.isRegularFile(jar)) {
      throw new HolmgangException(
          "expected a built jar at " + jar + " -- run `mvn install` before -Pvalidation");
    }
    return jar;
  }
}
