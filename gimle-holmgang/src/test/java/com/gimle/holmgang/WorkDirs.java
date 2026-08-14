package com.gimle.holmgang;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/**
 * The one place cluster work-directory retention is decided: {@code
 * -Dgimle.holmgang.keepWorkDirs=onFailure|always|never} (default {@code onFailure} -- process logs
 * are the forensic artifact, so a failed run's directory survives while a green run leaves nothing
 * behind). Shared by the JUnit extension and the Gherkin cluster pool so both surfaces behave
 * identically.
 */
public final class WorkDirs {

  private WorkDirs() {}

  public static boolean shouldDelete(final boolean anyTestFailed) {
    final String policy =
        System.getProperty("gimle.holmgang.keepWorkDirs", "onFailure").toLowerCase(Locale.ROOT);
    return switch (policy) {
      case "always" -> false;
      case "never" -> true;
      case "onfailure" -> !anyTestFailed;
      default ->
          throw new HolmgangException(
              "unknown gimle.holmgang.keepWorkDirs policy: "
                  + policy
                  + " (expected onFailure, always, or never)");
    };
  }

  public static void deleteRecursively(final Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs)
                throws IOException {
              Files.deleteIfExists(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc)
                throws IOException {
              Files.deleteIfExists(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (final IOException e) {
      throw new HolmgangException("failed deleting cluster work directory " + root, e);
    }
  }
}
