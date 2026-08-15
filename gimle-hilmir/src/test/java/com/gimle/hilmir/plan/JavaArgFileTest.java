package com.gimle.hilmir.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaArgFileTest {

  @TempDir Path tempDir;

  @Test
  void rewrites_a_command_into_a_two_token_argfile_invocation() throws IOException {
    final Path argFile = tempDir.resolve("cmd.args");
    final List<String> rewritten =
        JavaArgFile.rewrite(
            List.of("java", "-Dfoo=bar", "-cp", "a:b:c", "com.example.Main"), argFile);

    assertEquals(2, rewritten.size());
    assertEquals("java", rewritten.get(0));
    assertEquals("@" + argFile.toAbsolutePath(), rewritten.get(1));

    final String contents = Files.readString(argFile);
    assertEquals("\"-Dfoo=bar\"\n\"-cp\"\n\"a:b:c\"\n\"com.example.Main\"\n", contents);
  }

  @Test
  void quotes_and_escapes_embedded_quotes_and_backslashes() throws IOException {
    final Path argFile = tempDir.resolve("escaped.args");
    JavaArgFile.rewrite(List.of("java", "C:\\path\\with \"quotes\""), argFile);

    final String contents = Files.readString(argFile);
    assertTrue(contents.contains("\"C:\\\\path\\\\with \\\"quotes\\\"\""));
  }
}
