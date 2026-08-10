package com.gimle.core.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link BundledSpa} sits behind a classloader lookup that can return either a {@code jar:} or a
 * {@code file:} URL depending on how the caller was launched -- both branches, plus the "not
 * bundled at all" case, are exercised here against real classpath entries (a real jar built with
 * {@link JarOutputStream}, a real exploded directory), not mocks, since the whole point of this
 * class is genuine cross-{@code FileSystem} {@code Path} behavior.
 */
class BundledSpaTest {

  @TempDir Path tempDir;

  @Test
  void resolves_a_file_scheme_classpath_resource_to_its_parent_directory() throws Exception {
    Path classesDir = tempDir.resolve("classes");
    Files.createDirectories(classesDir.resolve("console"));
    Files.writeString(classesDir.resolve("console").resolve("index.html"), "<html></html>");

    try (URLClassLoader loader = new URLClassLoader(new URL[] {classesDir.toUri().toURL()}, null)) {
      Optional<Path> resolved = BundledSpa.resolve(loader, "console/index.html");
      assertTrue(resolved.isPresent(), "expected the exploded console/ directory to resolve");
      assertTrue(Files.isRegularFile(resolved.get().resolve("index.html")));
    }
  }

  @Test
  void resolves_a_jar_scheme_classpath_resource_by_opening_it_as_a_filesystem() throws Exception {
    Path jarPath = tempDir.resolve("console-fixture.jar");
    try (JarOutputStream jarOut = new JarOutputStream(Files.newOutputStream(jarPath))) {
      jarOut.putNextEntry(new JarEntry("console/index.html"));
      jarOut.write("<html>from jar</html>".getBytes(StandardCharsets.UTF_8));
      jarOut.closeEntry();
      jarOut.putNextEntry(new JarEntry("console/assets/app.js"));
      jarOut.write("console.log(1);".getBytes(StandardCharsets.UTF_8));
      jarOut.closeEntry();
    }

    try (URLClassLoader loader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()}, null)) {
      Optional<Path> resolved = BundledSpa.resolve(loader, "console/index.html");
      assertTrue(resolved.isPresent(), "expected the bundled jar's console/ entry to resolve");
      Path indexHtml = resolved.get().resolve("index.html");
      assertTrue(Files.isRegularFile(indexHtml));
      assertEquals("<html>from jar</html>", Files.readString(indexHtml));
      assertTrue(Files.isRegularFile(resolved.get().resolve("assets/app.js")));
      // Releases the zip filesystem's open handle on the jar -- otherwise @TempDir cleanup can't
      // delete console-fixture.jar afterward on Windows (same class of issue as this project's
      // other RollingFileAppender-backed tests).
      resolved.get().getFileSystem().close();
    }
  }

  @Test
  void returns_empty_when_no_bundled_console_is_on_the_classpath() throws Exception {
    try (URLClassLoader loader = new URLClassLoader(new URL[] {tempDir.toUri().toURL()}, null)) {
      assertTrue(BundledSpa.resolve(loader, "console/index.html").isEmpty());
    }
  }

  @Test
  void resolves_a_different_marker_resource_for_a_second_bundled_spa() throws Exception {
    // Fafnir's own console jar bundles under the identical console/** prefix as gimle-console's --
    // no collision, since each process's classpath only ever carries the one console jar it
    // depends on -- but this test picks a distinct marker path to prove BundledSpa itself makes no
    // assumption about "console" being the only valid prefix.
    Path classesDir = tempDir.resolve("classes");
    Files.createDirectories(classesDir.resolve("fafnir-console"));
    Files.writeString(classesDir.resolve("fafnir-console").resolve("index.html"), "<html></html>");

    try (URLClassLoader loader = new URLClassLoader(new URL[] {classesDir.toUri().toURL()}, null)) {
      Optional<Path> resolved = BundledSpa.resolve(loader, "fafnir-console/index.html");
      assertTrue(resolved.isPresent());
      assertTrue(Files.isRegularFile(resolved.get().resolve("index.html")));
    }
  }
}
