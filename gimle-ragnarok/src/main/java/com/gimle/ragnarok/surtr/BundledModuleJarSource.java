package com.gimle.ragnarok.surtr;

import com.gimle.ragnarok.RagnarokException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The {@link ModuleJarSource} a shipped {@code ragnarok} binary defaults to: extracts {@code
 * gimle-ragnarok}'s own bundled {@code pause/pause-image.jar} classpath resource (embedded by
 * {@code gimle-ragnarok}'s own build -- see its {@code pom.xml}) to a temp file the first time it's
 * asked for, the same {@code Files.createTempFile}/{@code deleteOnExit} idiom {@code gimle-cli}'s
 * own {@code ArtifactSetCommand} already uses for a generated artifact. Serves exactly one artifact
 * id, {@code "pause"} -- unlike {@code ExampleModuleJarSource} (a repo-checkout-local fixture never
 * shipped with the tool), this has no relative-path assumption about where {@code ragnarok} is run
 * from.
 */
public final class BundledModuleJarSource implements ModuleJarSource {

  private static final String ARTIFACT_ID = "pause";
  private static final String MODULE_NAME = "com.gimle.ragnarok.pause";
  private static final String RESOURCE = "pause/pause-image.jar";

  private volatile Path extractedJar;

  @Override
  public String moduleName(final String artifactId) {
    requireKnown(artifactId);
    return MODULE_NAME;
  }

  @Override
  public synchronized Path jar(final String artifactId) {
    requireKnown(artifactId);
    if (extractedJar == null) {
      extractedJar = extract();
    }
    return extractedJar;
  }

  private static void requireKnown(final String artifactId) {
    if (!ARTIFACT_ID.equals(artifactId)) {
      throw new RagnarokException(
          "unknown bundled module: " + artifactId + " (expected '" + ARTIFACT_ID + "')");
    }
  }

  private Path extract() {
    try (InputStream in =
        BundledModuleJarSource.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new RagnarokException("no bundled module resource on the classpath: " + RESOURCE);
      }
      final Path tempJar = Files.createTempFile("gimle-ragnarok-pause-", ".jar");
      tempJar.toFile().deleteOnExit();
      Files.copy(in, tempJar, StandardCopyOption.REPLACE_EXISTING);
      return tempJar;
    } catch (final IOException e) {
      throw new RagnarokException("failed extracting bundled module resource: " + RESOURCE, e);
    }
  }
}
