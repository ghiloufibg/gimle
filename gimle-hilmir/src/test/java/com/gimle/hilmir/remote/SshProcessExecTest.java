package com.gimle.hilmir.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Asserts the exact {@code ssh}/{@code scp} argv {@link SshProcessExec} builds, without invoking a
 * real process -- the same spirit as testing {@code com.gimle.hilmir.plan.JavaArgFile#rewrite}'s
 * argument construction without running {@code java}. {@link SshProcessExec#pinHostKey} shells out
 * to {@code ssh-keyscan}/{@code ssh-keygen} against a real reachable host, so it -- unlike the pure
 * argv-building methods here -- has no equivalent hermetic unit coverage; {@code
 * RemoteDispatchTest} covers the {@link RemoteExec} contract it participates in via a fake instead.
 */
class SshProcessExecTest {

  private static final Path KNOWN_HOSTS = Path.of("/data/known_hosts");

  private static SshProcessExec exec() {
    return new SshProcessExec(KNOWN_HOSTS);
  }

  private static ResolvedSshTarget target(
      final Optional<String> user,
      final Optional<Integer> port,
      final Optional<String> identityFile) {
    return new ResolvedSshTarget(
        "m1",
        "gimle-1.example.com",
        user,
        port,
        identityFile,
        "/opt/gimle",
        Optional.empty(),
        Optional.empty());
  }

  @Test
  void ssh_command_carries_strict_host_key_checking_against_the_per_topology_known_hosts_file() {
    final List<String> command =
        exec()
            .sshCommand(
                target(Optional.empty(), Optional.empty(), Optional.empty()),
                List.of("bin/hilmir", "status"));

    assertEquals("ssh", command.get(0));
    assertTrue(command.containsAll(List.of("-o", "StrictHostKeyChecking=yes")));
    assertTrue(command.containsAll(List.of("-o", "UserKnownHostsFile=" + KNOWN_HOSTS)));
    assertTrue(command.containsAll(List.of("-o", "BatchMode=yes")));
    assertTrue(command.containsAll(List.of("-o", "ConnectTimeout=10")));
  }

  @Test
  void ssh_command_omits_user_port_and_identity_flags_when_unset() {
    final List<String> command =
        exec()
            .sshCommand(
                target(Optional.empty(), Optional.empty(), Optional.empty()),
                List.of("bin/hilmir", "status"));

    assertEquals("gimle-1.example.com", command.get(command.size() - 2));
    assertTrue(command.stream().noneMatch("-p"::equals));
    assertTrue(command.stream().noneMatch("-i"::equals));
  }

  @Test
  void ssh_command_includes_the_user_port_and_identity_flags_when_set() {
    final List<String> command =
        exec()
            .sshCommand(
                target(
                    Optional.of("ubuntu"), Optional.of(2222), Optional.of("/home/op/id_ed25519")),
                List.of("bin/hilmir", "status"));

    assertEquals("ubuntu@gimle-1.example.com", command.get(command.size() - 2));
    assertTrue(command.containsAll(List.of("-p", "2222")));
    assertTrue(command.containsAll(List.of("-i", "/home/op/id_ed25519")));
  }

  @Test
  void ssh_command_cds_into_the_install_dir_before_running_the_remote_command() {
    final List<String> command =
        exec()
            .sshCommand(
                target(Optional.empty(), Optional.empty(), Optional.empty()),
                List.of("/opt/gimle/bin/hilmir", "status", "--machine", "m1"));

    final String remoteShell = command.get(command.size() - 1);
    assertEquals(
        "cd '/opt/gimle' && '/opt/gimle/bin/hilmir' 'status' '--machine' 'm1'", remoteShell);
  }

  @Test
  void ssh_command_raw_never_cds_into_the_install_dir() {
    final List<String> command =
        exec()
            .sshCommandRaw(
                target(Optional.empty(), Optional.empty(), Optional.empty()),
                List.of("test", "-x", "/opt/gimle/bin/hilmir"));

    final String remoteShell = command.get(command.size() - 1);
    assertEquals("'test' '-x' '/opt/gimle/bin/hilmir'", remoteShell);
    assertTrue(command.containsAll(List.of("-o", "StrictHostKeyChecking=yes")));
  }

  @Test
  void ssh_command_shell_quotes_an_argument_containing_a_single_quote() {
    final List<String> command =
        exec()
            .sshCommand(
                target(Optional.empty(), Optional.empty(), Optional.empty()),
                List.of("/opt/gimle/bin/hilmir", "up", "-f", "it's-a-topology.yaml"));

    final String remoteShell = command.get(command.size() - 1);
    assertTrue(remoteShell.contains("'it'\\''s-a-topology.yaml'"));
  }

  @Test
  void scp_command_uses_uppercase_p_for_port_unlike_ssh() {
    final List<String> command =
        exec()
            .scpCommand(
                target(Optional.empty(), Optional.of(2222), Optional.empty()),
                Path.of("/local/topology.yaml"),
                "/opt/gimle/topology.yaml");

    assertEquals("scp", command.get(0));
    assertTrue(command.containsAll(List.of("-P", "2222")));
    assertTrue(command.stream().noneMatch("-p"::equals));
  }

  @Test
  void scp_command_carries_the_local_file_and_the_remote_destination_last() {
    final List<String> command =
        exec()
            .scpCommand(
                target(Optional.of("ubuntu"), Optional.empty(), Optional.empty()),
                Path.of("/local/topology.yaml"),
                "/opt/gimle/topology.yaml");

    assertEquals("/local/topology.yaml", command.get(command.size() - 2));
    assertEquals(
        "ubuntu@gimle-1.example.com:/opt/gimle/topology.yaml", command.get(command.size() - 1));
  }
}
