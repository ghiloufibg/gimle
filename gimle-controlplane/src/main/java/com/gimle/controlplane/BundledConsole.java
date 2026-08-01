package com.gimle.controlplane;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the web console's built assets off {@code ControlPlaneMain}'s own classpath, bundled
 * there by depending on {@code gimle-console} (a Maven module with no Java sources -- just Bun's
 * {@code dist/} output copied to {@code console/**} inside its jar; see that module's {@code
 * pom.xml}). Handles the two shapes this classloader lookup can return: a real {@code jar:} URI
 * (the normal case, running against installed dependency jars) and a plain {@code file:} URI
 * (running from exploded classes -- an IDE run, or {@code -cp target/classes;...}).
 *
 * <p>A jar-backed {@link java.nio.file.FileSystem} opened here is never explicitly closed: this is
 * called once at process startup and the resolved path needs to stay readable for the server's
 * entire lifetime, the same convention {@code GimleLogging}'s platform appenders already use for
 * process-lifetime resources.
 */
final class BundledConsole {

  private static final String MARKER_RESOURCE = "console/index.html";

  private BundledConsole() {}

  static Optional<Path> resolve(ClassLoader classLoader) throws IOException {
    URL marker = classLoader.getResource(MARKER_RESOURCE);
    if (marker == null) {
      return Optional.empty();
    }
    URI uri;
    try {
      uri = marker.toURI();
    } catch (URISyntaxException e) {
      throw new IOException("malformed bundled console resource URL: " + marker, e);
    }
    if ("jar".equals(uri.getScheme())) {
      String jarUriString = uri.toString();
      int bang = jarUriString.indexOf('!');
      URI jarUri = URI.create(jarUriString.substring(0, bang));
      return Optional.of(FileSystems.newFileSystem(jarUri, Map.of()).getPath("/console"));
    }
    // file: -- exploded classes (IDE run, or -cp target/classes;...): the marker resource's own
    // parent directory is the console/ root directly, no filesystem-opening needed.
    return Optional.of(Path.of(uri).getParent());
  }
}
