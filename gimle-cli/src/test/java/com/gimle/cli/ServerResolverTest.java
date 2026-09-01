package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The precedence rule, exercised directly rather than through {@link GimleCli}: the environment
 * value is a parameter here, which is the only way to test the middle rung of a three-rung order in
 * a JVM that cannot set its own environment variables.
 */
class ServerResolverTest {

  @TempDir Path tempDir;

  private Path configPath;
  private ByteArrayOutputStream errBuffer;
  private PrintStream err;

  @BeforeEach
  void prepare() {
    configPath = tempDir.resolve("config");
    errBuffer = new ByteArrayOutputStream();
    err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
  }

  private String stderr() {
    return errBuffer.toString(StandardCharsets.UTF_8);
  }

  private void writeConfig(String content) throws IOException {
    Files.writeString(configPath, content, StandardCharsets.UTF_8);
  }

  private void writeProdContext() throws IOException {
    writeConfig(
        """
        currentContext: "prod"
        contexts:
          - name: "prod"
            server: "cp.prod:8080"
        """);
  }

  @Test
  void the_server_flag_wins_over_both_the_environment_and_the_current_context() throws Exception {
    writeProdContext();

    assertEquals("flag:1", ServerResolver.resolve("flag:1", "env:2", configPath, err));
  }

  @Test
  void the_environment_wins_over_the_current_context() throws Exception {
    writeProdContext();

    assertEquals("env:2", ServerResolver.resolve(null, "env:2", configPath, err));
  }

  @Test
  void the_current_context_is_used_when_neither_the_flag_nor_the_environment_is_set()
      throws Exception {
    writeProdContext();

    assertEquals("cp.prod:8080", ServerResolver.resolve(null, null, configPath, err));
  }

  @Test
  void a_blank_flag_or_environment_value_counts_as_unset() throws Exception {
    writeProdContext();

    assertEquals("cp.prod:8080", ServerResolver.resolve("   ", "", configPath, err));
  }

  @Test
  void no_config_file_at_all_reports_all_three_ways_to_configure_one() {
    CliException failure =
        assertThrows(CliException.class, () -> ServerResolver.resolve(null, null, configPath, err));

    assertTrue(failure.getMessage().contains("no control-plane server configured"));
    assertTrue(failure.getMessage().contains("--server"));
    assertTrue(failure.getMessage().contains("GIMLE_SERVER"));
    assertTrue(failure.getMessage().contains("gimle context use"));
    assertEquals("", stderr());
  }

  @Test
  void a_malformed_config_file_degrades_to_a_warning_rather_than_swallowing_the_command()
      throws Exception {
    writeConfig("contexts: \"not a list\"\n");

    assertThrows(CliException.class, () -> ServerResolver.resolve(null, null, configPath, err));

    assertTrue(stderr().contains("warning: ignoring " + configPath), stderr());
    assertTrue(stderr().contains("contexts must be a list"), stderr());
  }

  /** Precedence is also what keeps a broken file from costing anything on an explicit --server. */
  @Test
  void a_malformed_config_file_is_never_read_at_all_when_the_flag_is_present() throws Exception {
    writeConfig("this: [is: not: valid: yaml\n");

    assertEquals("flag:1", ServerResolver.resolve("flag:1", null, configPath, err));
    assertEquals("", stderr());
  }

  @Test
  void a_current_context_naming_something_undefined_warns_and_falls_through() throws Exception {
    writeConfig(
        """
        currentContext: "staging"
        contexts:
          - name: "prod"
            server: "cp.prod:8080"
        """);

    assertThrows(CliException.class, () -> ServerResolver.resolve(null, null, configPath, err));

    assertTrue(stderr().contains("current context 'staging' is not defined"), stderr());
  }

  @Test
  void a_config_file_with_contexts_but_none_selected_falls_through_without_a_warning()
      throws Exception {
    writeConfig(
        """
        contexts:
          - name: "prod"
            server: "cp.prod:8080"
        """);

    assertThrows(CliException.class, () -> ServerResolver.resolve(null, null, configPath, err));

    assertFalse(stderr().contains("warning"), stderr());
  }
}
