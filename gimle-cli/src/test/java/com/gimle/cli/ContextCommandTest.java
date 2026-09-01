package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code context} verbs against a real config file, driven through their own command class. */
class ContextCommandTest {

  @TempDir Path tempDir;

  private Path configPath;
  private ByteArrayOutputStream outBuffer;
  private PrintStream out;

  @BeforeEach
  void prepare() {
    configPath = tempDir.resolve("gimle").resolve("config");
    outBuffer = new ByteArrayOutputStream();
    out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
  }

  private ContextCommand command() {
    return new ContextCommand(OutputFormat.Kind.TABLE, out, configPath);
  }

  private ContextCommand jsonCommand() {
    return new ContextCommand(OutputFormat.Kind.JSON, out, configPath);
  }

  private String stdout() {
    return outBuffer.toString(StandardCharsets.UTF_8);
  }

  /**
   * What a later ordinary command would resolve off this config -- its own stream, so a resolution
   * warning can never be mistaken for command output above.
   */
  private String resolvedServer() {
    return ServerResolver.resolve(
        null,
        null,
        configPath,
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
  }

  private void set(String name, String server) {
    command().run(List.of("set", name), server);
    outBuffer.reset();
  }

  @Test
  void the_first_context_set_creates_the_file_and_becomes_current() {
    command().run(List.of("set", "dev"), "127.0.0.1:8080");

    assertTrue(Files.exists(configPath));
    assertTrue(stdout().contains("context/dev set (127.0.0.1:8080)"), stdout());
    assertTrue(stdout().contains("current context"), stdout());
    assertEquals("127.0.0.1:8080", resolvedServer());
  }

  @Test
  void a_later_context_is_added_without_stealing_the_current_selection() {
    set("dev", "127.0.0.1:8080");

    command().run(List.of("set", "prod"), "cp.prod:8080");

    assertFalse(stdout().contains("current context"), stdout());
    assertEquals("127.0.0.1:8080", resolvedServer());
  }

  @Test
  void setting_an_existing_context_again_replaces_its_endpoint_rather_than_duplicating_it() {
    set("dev", "127.0.0.1:8080");

    command().run(List.of("set", "dev"), "127.0.0.1:9090");
    outBuffer.reset();
    command().run(List.of("list"), null);

    assertEquals(1, stdout().lines().filter(line -> line.startsWith("dev\t")).count(), stdout());
    assertTrue(stdout().contains("127.0.0.1:9090"), stdout());
  }

  @Test
  void use_switches_which_context_a_later_command_resolves() {
    set("dev", "127.0.0.1:8080");
    set("prod", "cp.prod:8080");

    command().run(List.of("use", "prod"), null);

    assertTrue(stdout().contains("context/prod selected"), stdout());
    assertEquals("cp.prod:8080", resolvedServer());
  }

  @Test
  void list_marks_exactly_one_context_current() {
    set("dev", "127.0.0.1:8080");
    set("prod", "cp.prod:8080");

    jsonCommand().run(List.of("list"), null);

    List<Map<String, Object>> rows = Json.asObjectList(Json.parse(stdout()));
    assertEquals(2, rows.size(), stdout());
    assertEquals(1, rows.stream().filter(row -> Boolean.TRUE.equals(row.get("current"))).count());
  }

  @Test
  void show_without_a_name_describes_the_current_context() {
    set("dev", "127.0.0.1:8080");

    jsonCommand().run(List.of("show"), null);

    Map<String, Object> row = Json.asObject(Json.parse(stdout()));
    assertEquals("dev", row.get("name"));
    assertEquals(Boolean.TRUE, row.get("current"));
  }

  @Test
  void a_bare_context_invocation_lists_rather_than_failing() {
    set("dev", "127.0.0.1:8080");

    command().run(List.of(), null);

    assertTrue(stdout().contains("dev"), stdout());
  }

  @Test
  void delete_removes_the_context_and_clears_it_when_it_was_the_current_one() {
    set("dev", "127.0.0.1:8080");

    command().run(List.of("delete", "dev"), null);

    assertTrue(stdout().contains("no current context selected any more"), stdout());
    outBuffer.reset();
    command().run(List.of("list"), null);
    assertTrue(stdout().contains("no contexts configured"), stdout());
  }

  @Test
  void deleting_one_of_two_leaves_the_other_selected() {
    set("dev", "127.0.0.1:8080");
    set("prod", "cp.prod:8080");

    command().run(List.of("delete", "prod"), null);

    assertFalse(stdout().contains("no current context"), stdout());
    assertEquals("127.0.0.1:8080", resolvedServer());
  }

  @Test
  void listing_with_no_config_file_at_all_says_how_to_create_one() {
    command().run(List.of("list"), null);

    assertTrue(stdout().contains("no contexts configured in " + configPath), stdout());
    assertTrue(stdout().contains("gimle context set"), stdout());
    assertFalse(Files.exists(configPath), "listing must not create the file");
  }

  @Test
  void a_malformed_config_file_names_the_file_and_what_is_wrong_with_it() throws Exception {
    Files.createDirectories(configPath.getParent());
    Files.writeString(configPath, "contexts:\n  - name: \"dev\"\n", StandardCharsets.UTF_8);

    CliException failure =
        assertThrows(CliException.class, () -> command().run(List.of("list"), null));

    assertTrue(failure.getMessage().contains(configPath.toString()), failure.getMessage());
    assertTrue(failure.getMessage().contains("non-empty server"), failure.getMessage());
  }

  @Test
  void a_config_file_that_is_not_yaml_at_all_is_reported_as_such() throws Exception {
    Files.createDirectories(configPath.getParent());
    Files.writeString(configPath, "contexts: [unterminated\n", StandardCharsets.UTF_8);

    CliException failure =
        assertThrows(CliException.class, () -> command().run(List.of("list"), null));

    assertTrue(failure.getMessage().contains("is not valid YAML"), failure.getMessage());
  }

  @Test
  void using_a_context_that_does_not_exist_lists_the_ones_that_do() {
    set("dev", "127.0.0.1:8080");

    CliException failure =
        assertThrows(CliException.class, () -> command().run(List.of("use", "staging"), null));

    assertTrue(failure.getMessage().contains("no such context: staging"), failure.getMessage());
    assertTrue(failure.getMessage().contains("known contexts: dev"), failure.getMessage());
  }

  @Test
  void set_without_a_server_flag_prints_the_subcommand_usage() {
    CliException failure =
        assertThrows(CliException.class, () -> command().run(List.of("set", "dev"), null));

    assertTrue(
        failure.getMessage().contains("usage: gimle context set <name> --server host:port"),
        failure.getMessage());
  }

  @Test
  void a_url_is_rejected_where_a_host_and_port_is_expected() {
    CliException failure =
        assertThrows(
            CliException.class,
            () -> command().run(List.of("set", "dev"), "http://127.0.0.1:8080"));

    assertTrue(failure.getMessage().contains("not a URL"), failure.getMessage());
  }

  @Test
  void a_context_name_with_a_path_separator_in_it_is_rejected() {
    CliException failure =
        assertThrows(
            CliException.class, () -> command().run(List.of("set", "../escape"), "127.0.0.1:8080"));

    assertTrue(failure.getMessage().contains("invalid context name"), failure.getMessage());
  }

  @Test
  void an_unknown_action_prints_the_verb_usage() {
    CliException failure =
        assertThrows(CliException.class, () -> command().run(List.of("switch", "dev"), null));

    assertTrue(failure.getMessage().contains("usage: gimle context list"), failure.getMessage());
  }

  @Test
  void the_written_file_is_owner_only_where_the_filesystem_supports_it() throws Exception {
    set("dev", "127.0.0.1:8080");

    if (!configPath.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      return;
    }
    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(configPath);
    assertEquals(
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions);
    assertFalse(
        Files.getPosixFilePermissions(configPath.getParent())
            .contains(PosixFilePermission.OTHERS_READ));
  }

  @Test
  void the_written_file_reads_back_as_the_same_config() {
    set("dev", "127.0.0.1:8080");
    set("prod", "cp.prod:8080");

    CliConfig reloaded = CliConfig.load(configPath);

    assertEquals("dev", reloaded.currentContext().orElseThrow());
    assertEquals(
        List.of("dev", "prod"), reloaded.contexts().stream().map(CliContext::name).toList());
    assertEquals("cp.prod:8080", reloaded.find("prod").orElseThrow().server());
  }
}
