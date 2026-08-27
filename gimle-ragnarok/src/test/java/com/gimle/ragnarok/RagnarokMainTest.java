package com.gimle.ragnarok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RagnarokMain#run}'s top-level dispatch: every user-input mistake reachable from a verb's
 * own args must surface as a clean {@code error: ...} line and exit 1, never an uncaught stack
 * trace -- a real end user has no repo to grep a class name in.
 */
final class RagnarokMainTest {

  @Test
  void a_missing_module_jar_is_a_clean_error_not_an_uncaught_stack_trace(
      @TempDir final Path tempDir) throws IOException {
    final Path target = tempDir.resolve("target.yaml");
    Files.writeString(target, "controlPlaneBaseUrls: [http://127.0.0.1:1]\n");
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    final int exitCode =
        RagnarokMain.run(
            new String[] {
              "stress",
              "--target",
              target.toString(),
              "--module-jar",
              tempDir.resolve("does-not-exist.jar").toString()
            },
            new PrintStream(OutputStream.nullOutputStream()),
            new PrintStream(errBytes));

    assertEquals(1, exitCode);
    final String err = errBytes.toString(StandardCharsets.UTF_8);
    assertTrue(err.startsWith("error: "), err);
    assertFalse(err.contains("Exception"), err);
    assertFalse(err.contains("\tat "), err);
  }
}
