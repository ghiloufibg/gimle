package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The extension seam, exercised the way the shipped CLI actually loads code: unnamed, from the
 * classpath. {@code EchoCliExtension} is declared only in {@code
 * src/test/resources/META-INF/services/}, so a seam that relied on a {@code module-info} {@code
 * provides} directive alone would find nothing here rather than in an operator's terminal.
 */
class CliExtensionSeamTest {

  private final ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
  private final PrintStream err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);

  @Test
  void a_provider_on_the_classpath_is_discovered_and_dispatched_to() {
    int exitCode =
        GimleCli.run(new String[] {"seam-echo", "a", "b", "--server", "127.0.0.1:1"}, out, err);

    assertEquals(0, exitCode, errBuffer.toString(StandardCharsets.UTF_8));
    assertEquals("seam-echo server=127.0.0.1:1 args=a,b", stdout().trim());
  }

  @Test
  void a_verb_with_no_provider_still_produces_the_unknown_verb_error() {
    int exitCode = GimleCli.run(new String[] {"nosuchverb", "--server", "127.0.0.1:1"}, out, err);

    assertEquals(1, exitCode);
    assertTrue(stderr().contains("usage: gimle <verb> <resource>"), stderr());
    assertTrue(stdout().isEmpty(), stdout());
  }

  @Test
  void a_discovered_provider_contributes_its_usage_line_to_the_top_level_help() {
    GimleCli.run(new String[] {"--help"}, out, err);

    assertTrue(stdout().contains("seam-echo [args...]"), stdout());
  }

  @Test
  void gimle_help_on_a_discovered_verb_shows_its_own_usage_not_the_full_listing() {
    int exitCode = GimleCli.run(new String[] {"seam-echo", "-h"}, out, err);

    assertEquals(0, exitCode, errBuffer.toString(StandardCharsets.UTF_8));
    assertEquals("usage: gimle seam-echo [args...]", stdout().trim());
    // The defect this guards: before an extension's verb was consulted here, "-h" on any verb
    // this switch didn't statically know about -- including a genuinely discovered one -- fell
    // through to the entire multi-verb top-level listing instead of one scoped line.
    assertFalse(stdout().contains("usage: gimle <verb> <resource>"), stdout());
  }

  @Test
  void the_reader_handed_to_an_extension_exposes_reads_only() {
    // A compile-time property, asserted structurally rather than by calling anything: nothing on
    // ClusterReader can mutate cluster state, so an extension has no write path to reach for.
    for (var method : com.gimle.cli.spi.ClusterReader.class.getMethods()) {
      assertFalse(
          method.getName().equals("put")
              || method.getName().equals("post")
              || method.getName().equals("patch")
              || method.getName().equals("delete"),
          "ClusterReader must expose no mutating method, found: " + method.getName());
    }
  }

  private String stdout() {
    return outBuffer.toString(StandardCharsets.UTF_8);
  }

  private String stderr() {
    return errBuffer.toString(StandardCharsets.UTF_8);
  }
}
