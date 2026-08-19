package com.gimle.hilmir.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.RuntimeSettings;
import com.gimle.hilmir.topology.SshSettings;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResolvedSshTargetTest {

  private static final RuntimeSettings NO_RUNTIME_SETTINGS =
      new RuntimeSettings(Optional.empty(), Optional.empty(), Optional.empty(), false);

  @Test
  void defaults_to_the_bare_host_and_the_default_install_dir_when_nothing_is_configured() {
    final Machine machine = new Machine("m1", "gimle-1.example.com");

    final ResolvedSshTarget target =
        ResolvedSshTarget.resolve(machine, NO_RUNTIME_SETTINGS, SshCliFlags.NONE);

    assertEquals("m1", target.machineName());
    assertEquals("gimle-1.example.com", target.host());
    assertEquals(Optional.empty(), target.user());
    assertEquals(Optional.empty(), target.port());
    assertEquals(Optional.empty(), target.identityFile());
    assertEquals(ResolvedSshTarget.DEFAULT_INSTALL_DIR, target.installDir());
    assertEquals(
        ResolvedSshTarget.DEFAULT_INSTALL_DIR + "/bin/hilmir", target.remoteHilmirBinary());
  }

  @Test
  void a_topology_wide_runtime_default_applies_when_no_machine_override_exists() {
    final Machine machine = new Machine("m1", "h1");
    final RuntimeSettings runtimeSettings =
        withRuntimeSsh(
            new SshSettings(
                Optional.of("ubuntu"),
                Optional.of(2222),
                Optional.empty(),
                Optional.of("/srv/gimle")));

    final ResolvedSshTarget target =
        ResolvedSshTarget.resolve(machine, runtimeSettings, SshCliFlags.NONE);

    assertEquals(Optional.of("ubuntu"), target.user());
    assertEquals(Optional.of(2222), target.port());
    assertEquals("/srv/gimle", target.installDir());
  }

  @Test
  void a_per_machine_override_wins_over_the_runtime_wide_default() {
    final Machine machine =
        new Machine(
            "m1",
            "h1",
            Optional.of(
                new SshSettings(
                    Optional.of("deploy"), Optional.empty(), Optional.empty(), Optional.empty())));
    final RuntimeSettings runtimeSettings =
        withRuntimeSsh(
            new SshSettings(
                Optional.of("ubuntu"), Optional.of(2222), Optional.empty(), Optional.empty()));

    final ResolvedSshTarget target =
        ResolvedSshTarget.resolve(machine, runtimeSettings, SshCliFlags.NONE);

    // machine.ssh.user wins, but machine.ssh leaves port unset, so the runtime-wide default fills
    // it in -- precedence is per field, not "whichever tier has anything set wins for everything".
    assertEquals(Optional.of("deploy"), target.user());
    assertEquals(Optional.of(2222), target.port());
  }

  @Test
  void a_cli_flag_wins_over_both_the_machine_and_runtime_wide_settings() {
    final Machine machine =
        new Machine(
            "m1",
            "h1",
            Optional.of(
                new SshSettings(
                    Optional.of("deploy"), Optional.empty(), Optional.empty(), Optional.empty())));
    final SshCliFlags cliFlags =
        new SshCliFlags(
            Optional.of("operator"), Optional.empty(), Optional.empty(), Optional.empty());

    final ResolvedSshTarget target =
        ResolvedSshTarget.resolve(machine, NO_RUNTIME_SETTINGS, cliFlags);

    assertEquals(Optional.of("operator"), target.user());
  }

  @Test
  void a_cli_install_dir_override_wins_over_the_default() {
    final Machine machine = new Machine("m1", "h1");
    final SshCliFlags cliFlags =
        new SshCliFlags(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("/custom"));

    final ResolvedSshTarget target =
        ResolvedSshTarget.resolve(machine, NO_RUNTIME_SETTINGS, cliFlags);

    assertEquals("/custom", target.installDir());
  }

  private static RuntimeSettings withRuntimeSsh(final SshSettings ssh) {
    return new RuntimeSettings(
        Optional.empty(), Optional.empty(), Optional.<Path>empty(), false, Optional.of(ssh));
  }
}
