package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Context selection through the real {@link GimleCli} entry point against a real control plane --
 * the half {@link ServerResolverTest} cannot reach, since only a full invocation proves the
 * resolved address is the one actually dialled. Holds the system-properties lock for writing: it
 * repoints {@code gimle.cli.configFile} at its own temporary file for the duration.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ContextResolutionCliTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessCluster cluster;
  private Path configPath;
  private String previousConfigProperty;
  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;
  private PrintStream out;
  private PrintStream err;

  @BeforeEach
  void startCluster() {
    cluster = InProcessCluster.start(tempDir);
    configPath = tempDir.resolve("gimle-config");
    previousConfigProperty = System.getProperty(CliConfig.CONFIG_FILE_PROPERTY);
    System.setProperty(CliConfig.CONFIG_FILE_PROPERTY, configPath.toString());
    outBuffer = new ByteArrayOutputStream();
    errBuffer = new ByteArrayOutputStream();
    out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
    err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
  }

  @AfterEach
  void stopCluster() {
    if (previousConfigProperty == null) {
      System.clearProperty(CliConfig.CONFIG_FILE_PROPERTY);
    } else {
      System.setProperty(CliConfig.CONFIG_FILE_PROPERTY, previousConfigProperty);
    }
    cluster.close();
  }

  private int run(String... args) {
    return GimleCli.run(args, out, err);
  }

  private String stdout() {
    return outBuffer.toString(StandardCharsets.UTF_8);
  }

  private String stderr() {
    return errBuffer.toString(StandardCharsets.UTF_8);
  }

  private void configureContext(String name, String server) {
    assertEquals(0, run("context", "set", name, "--server", server), stderr());
    assertEquals(0, run("context", "use", name), stderr());
    outBuffer.reset();
  }

  @Test
  void a_command_with_no_server_flag_reaches_the_current_context() {
    configureContext("local", cluster.address());

    assertEquals(0, run("get", "tenants"), stderr());

    // The bootstrap tenants every cluster starts with -- proof the read reached a real server,
    // not just that the command exited zero.
    assertTrue(stdout().contains("gimle-system"), stdout());
  }

  @Test
  void an_explicit_server_flag_beats_the_current_context() {
    configureContext("broken", "localhost:1");

    assertEquals(0, run("get", "tenants", "--server", cluster.address()), stderr());
  }

  @Test
  void without_the_flag_that_same_broken_context_is_the_one_dialled() {
    configureContext("broken", "localhost:1");

    int exit = run("get", "tenants");

    assertNotEquals(0, exit);
    assertTrue(stderr().contains("localhost:1"), stderr());
  }

  @Test
  void the_context_verbs_themselves_need_no_server_at_all() {
    assertEquals(0, run("context", "list"), stderr());

    assertTrue(stdout().contains("no contexts configured"), stdout());
  }

  @Test
  void a_malformed_config_file_warns_but_still_lets_an_explicit_server_through() throws Exception {
    Files.writeString(configPath, "contexts: \"not a list\"\n", StandardCharsets.UTF_8);

    assertEquals(0, run("get", "tenants", "--server", cluster.address()), stderr());

    assertEquals("", stderr());
  }

  @Test
  void a_malformed_config_file_does_not_hide_why_no_server_was_resolved() throws Exception {
    Files.writeString(configPath, "contexts: \"not a list\"\n", StandardCharsets.UTF_8);

    int exit = run("get", "tenants");

    assertNotEquals(0, exit);
    assertTrue(stderr().contains("warning: ignoring " + configPath), stderr());
    assertTrue(stderr().contains("no control-plane server configured"), stderr());
  }
}
