package com.gimle.core.web;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a bundled single-page app's built assets off a process's own classpath -- the shared
 * mechanism both {@code gimle-controlplane}'s console ({@code gimle-console}, bundled at {@code
 * console/**}) and {@code gimle-fafnir}'s console ({@code gimle-fafnir-console}, bundled the same
 * way) rely on. Each is a Maven module with no Java sources of its own -- just Bun's {@code dist/}
 * output copied under a marker resource's parent directory inside its jar (see either module's
 * {@code pom.xml}). Handles the two shapes this classloader lookup can return: a real {@code jar:}
 * URI (the normal case, running against installed dependency jars) and a plain {@code file:} URI
 * (running from exploded classes -- an IDE run, or {@code -cp target/classes;...}).
 *
 * <p>A jar-backed {@link java.nio.file.FileSystem} opened here is never explicitly closed: this is
 * called once at process startup and the resolved path needs to stay readable for the server's
 * entire lifetime, the same convention {@code GimleLogging}'s platform appenders already use for
 * process-lifetime resources.
 */
public final class BundledSpa {

  private BundledSpa() {}

  /**
   * @param markerResource a classpath-relative resource path known to exist only inside the bundled
   *     SPA's own jar entry (e.g. {@code "console/index.html"}) -- its parent directory is treated
   *     as the SPA's static root.
   */
  public static Optional<Path> resolve(ClassLoader classLoader, String markerResource)
      throws IOException {
    URL marker = classLoader.getResource(markerResource);
    if (marker == null) {
      return Optional.empty();
    }
    URI uri;
    try {
      uri = marker.toURI();
    } catch (URISyntaxException e) {
      throw new IOException("malformed bundled SPA resource URL: " + marker, e);
    }
    if ("jar".equals(uri.getScheme())) {
      String jarUriString = uri.toString();
      int bang = jarUriString.indexOf('!');
      URI jarUri = URI.create(jarUriString.substring(0, bang));
      String rootInJar = markerResource.substring(0, markerResource.lastIndexOf('/'));
      return Optional.of(FileSystems.newFileSystem(jarUri, Map.of()).getPath("/" + rootInJar));
    }
    // file: -- exploded classes (IDE run, or -cp target/classes;...): the marker resource's own
    // parent directory is the SPA's static root directly, no filesystem-opening needed.
    return Optional.of(Path.of(uri).getParent());
  }
}
