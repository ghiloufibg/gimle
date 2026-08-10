package com.gimle.core.banner;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * This build's own version, read once at class-init time from {@code gimle-version.properties} -- a
 * resource {@code gimle-core}'s own {@code pom.xml} filters at build time (see that pom's {@code
 * <resources>} section), substituting the reactor's shared {@code ${project.version}} into a plain
 * classpath resource every process can read regardless of whether it's running from a built jar or
 * an IDE's exploded {@code target/classes} (unlike {@code Package.getImplementationVersion()},
 * which needs a real jar manifest and returns {@code null} from exploded classes -- exactly how
 * every {@code *Main} class here is actually launched, both in {@code gimle-maven-plugin}'s own
 * spawn commands and in tests).
 *
 * <p>{@code "dev"} is the deliberate fallback when the resource is missing entirely (a module that
 * somehow doesn't have {@code gimle-core} processed through this pom's resource step) -- a banner
 * showing "dev" is a visible, honest signal that something about the build is unusual, not a silent
 * wrong version number.
 */
public final class GimleVersion {

  private static final String VERSION = load();

  private GimleVersion() {}

  public static String current() {
    return VERSION;
  }

  private static String load() {
    try (InputStream in =
        GimleVersion.class.getClassLoader().getResourceAsStream("gimle-version.properties")) {
      if (in == null) {
        return "dev";
      }
      Properties props = new Properties();
      props.load(in);
      String version = props.getProperty("version");
      return version == null || version.isBlank() ? "dev" : version;
    } catch (IOException e) {
      return "dev";
    }
  }
}
