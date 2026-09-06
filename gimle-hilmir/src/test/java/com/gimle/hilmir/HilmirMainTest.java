package com.gimle.hilmir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class HilmirMainTest {

  @TempDir Path tempDir;

  private static final String HEALTHY_TOPOLOGY =
      """
      name: healthy
      machines:
        - {name: m1, host: 127.0.0.1}
      store:
        replicas:
          - {machine: m1}
      controlPlane:
        replicas:
          - {machine: m1}
      fafnir:
        keyFile: /etc/gimle/fafnir-secret.key
        replicas:
          - {machine: m1}
      agents:
        - {machine: m1, nodeId: node-a}
      """;

  private Path writeTopology(final String yaml) throws IOException {
    final Path file = tempDir.resolve("topology.yaml");
    Files.writeString(file, yaml, StandardCharsets.UTF_8);
    return file;
  }

  private record Result(int exitCode, String out, String err) {}

  private static Result run(final String... args) {
    final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final int exitCode =
        HilmirMain.run(
            args,
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    return new Result(
        exitCode,
        outBytes.toString(StandardCharsets.UTF_8),
        errBytes.toString(StandardCharsets.UTF_8));
  }

  @Test
  void validate_exits_zero_for_a_topology_with_no_error_severity_findings() throws IOException {
    // Deliberately still prints its two SINGLE_STORE/SINGLE_CONTROL_PLANE warnings: only an
    // ERROR-severity finding should ever affect the exit code.
    final Path file = writeTopology(HEALTHY_TOPOLOGY);
    final Result result = run("validate", "-f", file.toString());
    assertEquals(0, result.exitCode());
    assertTrue(result.out().contains("[WARNING] SINGLE_STORE"));
    assertTrue(!result.out().contains("[ERROR]"));
  }

  @Test
  void validate_exits_one_and_lists_errors_before_warnings_for_a_broken_topology()
      throws IOException {
    final Path file = writeTopology("name: broken\n");
    final Result result = run("validate", "-f", file.toString());
    assertEquals(1, result.exitCode());
    final int errorLine = result.out().indexOf("[ERROR] NO_MACHINES");
    final int warningLine = result.out().indexOf("[WARNING]");
    assertTrue(errorLine >= 0);
    assertTrue(warningLine < 0 || errorLine < warningLine);
  }

  @Test
  void plan_prints_the_resolved_commands_for_a_healthy_topology() throws IOException {
    final Path file = writeTopology(HEALTHY_TOPOLOGY);
    final Result result = run("plan", "-f", file.toString());
    assertEquals(0, result.exitCode());
    assertTrue(result.out().contains("machine: m1"));
    assertTrue(result.out().contains("STORE store-0"));
    assertTrue(result.out().contains("com.gimle.mimir.StoreMain"));
  }

  @Test
  void plan_filters_to_one_machine_when_requested() throws IOException {
    final Path file =
        writeTopology(
            """
            name: two-machine
            machines:
              - {name: m1, host: h1}
              - {name: m2, host: h2}
            store:
              replicas:
                - {machine: m1}
            controlPlane:
              replicas:
                - {machine: m1}
            fafnir:
              keyFile: /key
              replicas:
                - {machine: m2}
            """);
    final Result result = run("plan", "-f", file.toString(), "--machine", "m2");
    assertEquals(0, result.exitCode());
    assertTrue(result.out().contains("machine: m2"));
    assertTrue(!result.out().contains("machine: m1"));
  }

  @Test
  void plan_rejects_a_machine_name_the_topology_never_declares() throws IOException {
    final Path file = writeTopology(HEALTHY_TOPOLOGY);
    final Result result = run("plan", "-f", file.toString(), "--machine", "typo");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("no machine named 'typo'"));
    assertTrue(result.err().contains("m1"));
  }

  @Test
  void plan_and_up_reject_an_unknown_machine_name_the_same_way() throws IOException {
    final Path file = writeTopology(HEALTHY_TOPOLOGY);
    final Result planResult = run("plan", "-f", file.toString(), "--machine", "typo");
    final Result upResult = run("up", "-f", file.toString(), "--machine", "typo");
    assertEquals(upResult.exitCode(), planResult.exitCode());
    assertEquals(upResult.err(), planResult.err());
  }

  @Test
  void plan_aborts_with_findings_and_exit_one_when_the_topology_has_an_error() throws IOException {
    final Path file = writeTopology("name: broken\n");
    final Result result = run("plan", "-f", file.toString());
    assertEquals(1, result.exitCode());
    assertTrue(result.out().contains("[ERROR] NO_MACHINES"));
    assertTrue(!result.out().contains("machine:"));
  }

  @Test
  void reports_a_missing_file_flag_as_a_clean_error() {
    final Result result = run("validate");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("-f"));
  }

  @Test
  void reports_an_unreadable_topology_file_as_a_clean_error() {
    final Result result = run("validate", "-f", tempDir.resolve("missing.yaml").toString());
    assertEquals(1, result.exitCode());
    assertTrue(result.err().startsWith("error: "));
  }

  @Test
  void rejects_an_unknown_verb() {
    final Result result = run("frobnicate");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("usage:"));
  }

  @Test
  void up_requires_the_machine_flag() throws IOException {
    final Path file = writeTopology(HEALTHY_TOPOLOGY);
    final Result result = run("up", "-f", file.toString());
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("--machine"));
  }

  @Test
  void up_aborts_with_findings_before_launching_anything_when_the_topology_has_an_error()
      throws IOException {
    final Path file = writeTopology("name: broken\n");
    final Result result = run("up", "-f", file.toString(), "--machine", "m1");
    assertEquals(1, result.exitCode());
    assertTrue(result.out().contains("[ERROR] NO_MACHINES"));
  }

  @Test
  void down_requires_the_machine_flag() {
    final Result result = run("down");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("--machine"));
  }

  @Test
  void down_reports_a_clean_error_when_no_run_is_recorded_at_the_data_root() {
    final Result result =
        run("down", "--machine", "m1", "--data-root", tempDir.resolve("empty").toString());
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("no run recorded"));
  }

  @Test
  void status_requires_the_machine_flag() {
    final Result result = run("status");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("--machine"));
  }

  @Test
  void status_reports_a_clean_error_when_no_run_is_recorded_at_the_data_root() {
    final Result result =
        run("status", "--machine", "m1", "--data-root", tempDir.resolve("empty").toString());
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("no run recorded"));
  }

  @Test
  @Timeout(15)
  void up_with_remote_does_not_require_the_machine_flag() throws IOException {
    final Path file = writeTopology(HEALTHY_TOPOLOGY);
    // Port 1 refuses instantly on loopback (nothing ever listens there) -- fast enough to assert
    // against without a real SSH server, and without waiting out SshProcessExec's own connect
    // timeout. RemoteDispatch never lets a per-machine SSH failure escape as a HilmirException
    // (see RemoteDispatchTest), so an empty stderr here specifically proves --machine was never
    // demanded -- if it had been, "missing required flag: --machine" would show up as an error.
    final Result result = run("up", "-f", file.toString(), "--remote", "--ssh-port", "1");
    assertTrue(result.err().isEmpty());
  }

  @Test
  void down_with_remote_requires_the_file_flag() {
    final Result result = run("down", "--remote");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("--remote requires -f"));
  }

  @Test
  void status_with_remote_requires_the_file_flag() {
    final Result result = run("status", "--remote");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("--remote requires -f"));
  }

  @Test
  void pki_requires_the_init_subcommand() {
    final Result result = run("pki", "status");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("pki init"));
  }

  @Test
  void pki_init_requires_the_file_flag() {
    final Result result = run("pki", "init");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("-f"));
  }

  @Test
  void pki_init_refuses_a_topology_with_no_tls_material_dir() throws IOException {
    final Path file = writeTopology(HEALTHY_TOPOLOGY);
    final Result result = run("pki", "init", "-f", file.toString());
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("tls.materialDir"));
  }

  @Test
  void usage_lists_every_release_verb() {
    final Result result = run("frobnicate");
    assertTrue(result.err().contains("deploy -f"));
    assertTrue(result.err().contains("upgrade -f"));
    assertTrue(result.err().contains("rollback --release"));
    assertTrue(result.err().contains("undeploy --release"));
    assertTrue(result.err().contains("releases [-o json]"));
    assertTrue(result.err().contains("release-status <name>"));
  }

  @Test
  void usage_lists_the_store_add_and_remove_verbs() {
    final Result result = run("frobnicate");
    assertTrue(result.err().contains("store add <peerId>"));
    assertTrue(result.err().contains("store remove <peerId>"));
  }

  @Test
  void store_requires_a_known_subverb() {
    final Result result = run("store", "frobnicate");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("store add"));
    assertTrue(result.err().contains("store remove"));
  }

  @Test
  void store_requires_a_subverb_at_all() {
    final Result result = run("store");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("store add"));
  }

  @Test
  void store_add_requires_its_four_positional_arguments() {
    final Result result = run("store", "add", "peer-1", "127.0.0.1");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("hilmir store add"));
  }

  @Test
  void store_remove_requires_the_peer_id() {
    final Result result = run("store", "remove");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("hilmir store remove"));
  }

  @Test
  void store_add_requires_exactly_one_of_topology_or_server() {
    final Result result = run("store", "add", "peer-1", "127.0.0.1", "7100", "7200");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("--topology"));
    assertTrue(result.err().contains("--server"));
  }

  @Test
  void deploy_requires_the_file_flag() {
    final Result result = run("deploy");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("-f"));
  }

  @Test
  void undeploy_requires_the_release_flag() {
    final Result result = run("undeploy");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("--release"));
  }

  @Test
  void rollback_requires_the_release_flag() {
    final Result result = run("rollback");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("--release"));
  }

  @Test
  void release_status_requires_a_release_name() {
    final Result result = run("release-status");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("release name"));
  }

  @Test
  void usage_lists_the_enable_and_disable_verbs() {
    final Result result = run("frobnicate");
    assertTrue(result.err().contains("enable gateway --server"));
    assertTrue(result.err().contains("disable gateway --server"));
  }

  @Test
  void enable_requires_an_extension_name() {
    final Result result = run("enable");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("usage: hilmir enable gateway"));
  }

  @Test
  void enable_rejects_an_unknown_extension() {
    final Result result = run("enable", "frobnicate");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("unknown extension: frobnicate"));
  }

  @Test
  void disable_requires_an_extension_name() {
    final Result result = run("disable");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("usage: hilmir disable gateway"));
  }

  @Test
  void disable_rejects_an_unknown_extension() {
    final Result result = run("disable", "frobnicate");
    assertEquals(1, result.exitCode());
    assertTrue(result.err().contains("unknown extension: frobnicate"));
  }

  @Test
  void top_level_dash_h_prints_the_full_usage_instead_of_rejecting_the_verb() {
    final Result result = run("-h");
    assertEquals(0, result.exitCode());
    assertTrue(result.out().contains("usage: hilmir <verb> [args]"));
    assertTrue(result.err().isEmpty());
  }

  @Test
  void top_level_dash_dash_help_prints_the_full_usage_instead_of_rejecting_the_verb() {
    final Result result = run("--help");
    assertEquals(0, result.exitCode());
    assertTrue(result.out().contains("usage: hilmir <verb> [args]"));
  }

  @Test
  void enable_dash_h_prints_the_enable_usage_instead_of_listing_unknown_extension_dash_h() {
    final Result result = run("enable", "-h");
    assertEquals(0, result.exitCode());
    assertTrue(result.out().contains("usage: hilmir enable gateway"));
    assertTrue(result.err().isEmpty());
  }

  @Test
  void enable_gateway_dash_h_prints_the_enable_usage_without_needing_a_server() {
    // Without the fix this reaches EnableGatewayCommand's own flag parser, which rejects "-h" as
    // a flag expecting a value rather than recognizing it as help.
    final Result result = run("enable", "gateway", "-h");
    assertEquals(0, result.exitCode());
    assertTrue(result.out().contains("usage: hilmir enable gateway"));
    assertTrue(result.err().isEmpty());
  }

  @Test
  void disable_dash_h_prints_the_disable_usage_instead_of_listing_unknown_extension_dash_h() {
    final Result result = run("disable", "-h");
    assertEquals(0, result.exitCode());
    assertTrue(result.out().contains("usage: hilmir disable gateway"));
    assertTrue(result.err().isEmpty());
  }

  @Test
  void disable_gateway_dash_h_prints_the_disable_usage_without_needing_a_server() {
    final Result result = run("disable", "gateway", "--help");
    assertEquals(0, result.exitCode());
    assertTrue(result.out().contains("usage: hilmir disable gateway"));
    assertTrue(result.err().isEmpty());
  }
}
