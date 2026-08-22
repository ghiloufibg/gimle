package com.gimle.hilmir.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.plan.ResolvedRuntime;
import com.gimle.hilmir.topology.AndvariRole;
import com.gimle.hilmir.topology.ControlPlaneRole;
import com.gimle.hilmir.topology.FafnirRole;
import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.MuninnRole;
import com.gimle.hilmir.topology.RuntimeSettings;
import com.gimle.hilmir.topology.ServiceReplica;
import com.gimle.hilmir.topology.SshSettings;
import com.gimle.hilmir.topology.StoreReplica;
import com.gimle.hilmir.topology.StoreRole;
import com.gimle.hilmir.topology.TlsMaterial;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.Transport;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RemoteDispatchTest {

  private static final ResolvedRuntime RUNTIME =
      new ResolvedRuntime("java", "cp", Path.of("/data"));

  private static Topology topologyOf(final List<Machine> machines) {
    return topologyOf(
        machines,
        Transport.PLAINTEXT,
        Optional.empty(),
        new StoreRole(List.of()),
        new ControlPlaneRole(List.of()),
        new FafnirRole(Optional.empty(), List.of()),
        new MuninnRole(List.of()),
        new AndvariRole(List.of()));
  }

  private static Topology topologyOf(
      final List<Machine> machines,
      final Transport transport,
      final Optional<TlsMaterial> tls,
      final StoreRole store,
      final ControlPlaneRole controlPlane,
      final FafnirRole fafnir,
      final MuninnRole muninn,
      final AndvariRole andvari) {
    return new Topology(
        "t",
        transport,
        tls,
        machines,
        new RuntimeSettings(
            Optional.empty(), Optional.empty(), Optional.empty(), false, Optional.empty()),
        store,
        controlPlane,
        fafnir,
        muninn,
        andvari,
        List.of(),
        Map.of());
  }

  private static Machine bareMachine(final String name, final String host) {
    return new Machine(name, host, Optional.empty(), Optional.empty());
  }

  private static Machine machineWithArchive(
      final String name, final String host, final String archive) {
    return new Machine(
        name,
        host,
        Optional.empty(),
        Optional.of(
            new SshSettings(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(archive))));
  }

  private static PrintStream discardingOut() {
    return new PrintStream(ByteArrayOutputStream.nullOutputStream());
  }

  @Test
  void up_with_no_machine_filter_dispatches_to_every_machine_in_the_topology() {
    final Topology topology = topologyOf(List.of(bareMachine("m1", "h1"), bareMachine("m2", "h2")));
    final FakeRemoteExec exec = new FakeRemoteExec();

    final int exitCode =
        RemoteDispatch.up(
            topology,
            Path.of("topology.yaml"),
            Optional.empty(),
            Optional.empty(),
            SshCliFlags.NONE,
            RUNTIME,
            exec,
            discardingOut());

    assertEquals(0, exitCode);
    assertEquals(
        Set.of("m1", "m2"),
        exec.execCalls().stream()
            .map(FakeRemoteExec.ExecCall::machineName)
            .collect(Collectors.toSet()));
  }

  @Test
  void up_with_a_machine_filter_dispatches_to_only_that_machine() {
    final Topology topology = topologyOf(List.of(bareMachine("m1", "h1"), bareMachine("m2", "h2")));
    final FakeRemoteExec exec = new FakeRemoteExec();

    RemoteDispatch.up(
        topology,
        Path.of("topology.yaml"),
        Optional.of("m1"),
        Optional.empty(),
        SshCliFlags.NONE,
        RUNTIME,
        exec,
        discardingOut());

    assertEquals(1, exec.execCalls().size());
    assertEquals("m1", exec.execCalls().get(0).machineName());
  }

  @Test
  void up_with_an_unknown_machine_filter_throws() {
    final Topology topology = topologyOf(List.of(bareMachine("m1", "h1")));
    final FakeRemoteExec exec = new FakeRemoteExec();

    assertThrows(
        HilmirException.class,
        () ->
            RemoteDispatch.up(
                topology,
                Path.of("topology.yaml"),
                Optional.of("nope"),
                Optional.empty(),
                SshCliFlags.NONE,
                RUNTIME,
                exec,
                discardingOut()));
  }

  @Test
  void up_copies_the_topology_file_before_running_the_remote_up_command() {
    final Topology topology = topologyOf(List.of(bareMachine("m1", "h1")));
    final FakeRemoteExec exec = new FakeRemoteExec();

    RemoteDispatch.up(
        topology,
        Path.of("topology.yaml"),
        Optional.empty(),
        Optional.empty(),
        SshCliFlags.NONE,
        RUNTIME,
        exec,
        discardingOut());

    // A bare plaintext topology with no fafnir replicas needs no provisioning (the fake's execRaw
    // defaults to "binary already present") and no material distribution -- the topology file is
    // the only thing copied.
    assertEquals(1, exec.putFileCalls().size());
    assertEquals(Path.of("topology.yaml"), exec.putFileCalls().get(0).localFile());
    final List<String> command = exec.execCalls().get(0).command();
    assertTrue(command.contains("up"));
    assertTrue(command.contains("-f"));
    assertTrue(command.contains("--machine"));
    assertTrue(command.contains("m1"));
  }

  @Test
  void down_and_status_never_copy_a_file() {
    final Topology topology = topologyOf(List.of(bareMachine("m1", "h1")));
    final FakeRemoteExec downExec = new FakeRemoteExec();
    final FakeRemoteExec statusExec = new FakeRemoteExec();

    RemoteDispatch.down(
        topology, Optional.empty(), Optional.empty(), SshCliFlags.NONE, downExec, discardingOut());
    RemoteDispatch.status(
        topology,
        Optional.empty(),
        Optional.empty(),
        SshCliFlags.NONE,
        statusExec,
        discardingOut());

    assertTrue(downExec.putFileCalls().isEmpty());
    assertTrue(statusExec.putFileCalls().isEmpty());
    assertTrue(downExec.execCalls().get(0).command().contains("down"));
    assertTrue(statusExec.execCalls().get(0).command().contains("status"));
  }

  @Test
  void a_data_root_override_is_passed_through_to_every_verb() {
    final Topology topology = topologyOf(List.of(bareMachine("m1", "h1")));
    final FakeRemoteExec exec = new FakeRemoteExec();

    RemoteDispatch.status(
        topology,
        Optional.empty(),
        Optional.of(Path.of("/custom/data-root")),
        SshCliFlags.NONE,
        exec,
        discardingOut());

    final List<String> command = exec.execCalls().get(0).command();
    assertTrue(command.contains("--data-root"));
    assertTrue(command.contains("/custom/data-root"));
  }

  @Test
  void one_machines_failure_does_not_abort_the_others_and_the_aggregate_exit_code_is_non_zero() {
    final Topology topology = topologyOf(List.of(bareMachine("m1", "h1"), bareMachine("m2", "h2")));
    final FakeRemoteExec exec = new FakeRemoteExec();
    exec.exitCodeFor("m1", 1);

    final int exitCode =
        RemoteDispatch.status(
            topology, Optional.empty(), Optional.empty(), SshCliFlags.NONE, exec, discardingOut());

    assertEquals(1, exitCode);
    assertEquals(2, exec.execCalls().size());
  }

  @Test
  void a_transport_exception_on_one_machine_does_not_abort_the_others() {
    final Topology topology = topologyOf(List.of(bareMachine("m1", "h1"), bareMachine("m2", "h2")));
    final FakeRemoteExec exec = new FakeRemoteExec();
    exec.failFor("m1", new HilmirException("connection refused"));

    final int exitCode =
        RemoteDispatch.status(
            topology, Optional.empty(), Optional.empty(), SshCliFlags.NONE, exec, discardingOut());

    assertEquals(1, exitCode);
    assertEquals(2, exec.execCalls().size());
  }

  @Test
  void every_machine_succeeding_yields_a_zero_aggregate_exit_code() {
    final Topology topology = topologyOf(List.of(bareMachine("m1", "h1"), bareMachine("m2", "h2")));
    final FakeRemoteExec exec = new FakeRemoteExec();

    final int exitCode =
        RemoteDispatch.status(
            topology, Optional.empty(), Optional.empty(), SshCliFlags.NONE, exec, discardingOut());

    assertEquals(0, exitCode);
  }

  @Test
  void every_target_gets_its_ssh_host_key_pinned_before_any_command_runs() {
    final Topology topology = topologyOf(List.of(bareMachine("m1", "h1"), bareMachine("m2", "h2")));
    final FakeRemoteExec exec = new FakeRemoteExec();

    RemoteDispatch.status(
        topology, Optional.empty(), Optional.empty(), SshCliFlags.NONE, exec, discardingOut());

    assertEquals(Set.of("m1", "m2"), Set.copyOf(exec.pinHostKeyCalls()));
  }

  @Test
  void a_host_key_mismatch_on_one_machine_never_dispatches_to_it_but_does_not_abort_the_others() {
    final Topology topology = topologyOf(List.of(bareMachine("m1", "h1"), bareMachine("m2", "h2")));
    final FakeRemoteExec exec = new FakeRemoteExec();
    exec.hostKeyMismatchFor("m1");

    final int exitCode =
        RemoteDispatch.status(
            topology, Optional.empty(), Optional.empty(), SshCliFlags.NONE, exec, discardingOut());

    assertEquals(1, exitCode);
    assertEquals(1, exec.execCalls().size());
    assertEquals("m2", exec.execCalls().get(0).machineName());
  }

  @Test
  void up_provisions_a_machine_missing_the_hilmir_binary_from_its_configured_archive() {
    final Topology topology =
        topologyOf(List.of(machineWithArchive("m1", "h1", "/local/gimle-platform.tar.gz")));
    final FakeRemoteExec exec = new FakeRemoteExec();
    exec.execRawExitCodeFor("m1", 1); // "test -x bin/hilmir" fails: not installed yet

    RemoteDispatch.up(
        topology,
        Path.of("topology.yaml"),
        Optional.empty(),
        Optional.empty(),
        SshCliFlags.NONE,
        RUNTIME,
        exec,
        discardingOut());

    assertTrue(
        exec.putFileCalls().stream()
            .anyMatch(c -> c.localFile().equals(Path.of("/local/gimle-platform.tar.gz"))),
        "the configured archive should have been shipped");
    assertTrue(
        exec.execRawCalls().stream()
            .anyMatch(c -> c.command().stream().anyMatch(arg -> arg.contains("tar"))),
        "an unpack command should have run");
  }

  @Test
  void up_skips_provisioning_when_the_binary_is_already_present() {
    final Topology topology =
        topologyOf(List.of(machineWithArchive("m1", "h1", "/local/gimle-platform.tar.gz")));
    final FakeRemoteExec exec = new FakeRemoteExec();
    // execRaw defaults to exit 0: "test -x bin/hilmir" already succeeds.

    RemoteDispatch.up(
        topology,
        Path.of("topology.yaml"),
        Optional.empty(),
        Optional.empty(),
        SshCliFlags.NONE,
        RUNTIME,
        exec,
        discardingOut());

    assertTrue(
        exec.putFileCalls().stream()
            .noneMatch(c -> c.localFile().equals(Path.of("/local/gimle-platform.tar.gz"))),
        "no archive should be shipped when the binary is already there");
  }

  @Test
  void up_fails_clearly_when_provisioning_is_needed_and_no_archive_is_configured() {
    final Topology topology = topologyOf(List.of(bareMachine("m1", "h1")));
    final FakeRemoteExec exec = new FakeRemoteExec();
    exec.execRawExitCodeFor("m1", 1);

    final int exitCode =
        RemoteDispatch.up(
            topology,
            Path.of("topology.yaml"),
            Optional.empty(),
            Optional.empty(),
            SshCliFlags.NONE,
            RUNTIME,
            exec,
            discardingOut());

    assertEquals(1, exitCode);
    assertTrue(exec.execCalls().isEmpty(), "the actual up command must never run without a binary");
  }

  @Test
  void up_distributes_only_the_tls_and_fafnir_material_a_machine_actually_needs() {
    final Path materialDir = Path.of("/etc/gimle/tls");
    final Path keyFile = Path.of("/etc/gimle/fafnir-secret.key");
    final Topology topology =
        topologyOf(
            List.of(bareMachine("m1", "h1"), bareMachine("m2", "h2")),
            Transport.MTLS,
            Optional.of(new TlsMaterial(materialDir)),
            new StoreRole(List.of(new StoreReplica("m1", 9080, 9091))),
            new ControlPlaneRole(List.of(new ServiceReplica("m1", 8080))),
            new FafnirRole(Optional.of(keyFile), List.of(new ServiceReplica("m1", 9092))),
            new MuninnRole(List.of(new ServiceReplica("m2", 9093))),
            new AndvariRole(List.of()));
    final FakeRemoteExec exec = new FakeRemoteExec();

    RemoteDispatch.up(
        topology,
        Path.of("topology.yaml"),
        Optional.empty(),
        Optional.empty(),
        SshCliFlags.NONE,
        RUNTIME,
        exec,
        discardingOut());

    final Set<Path> m1Files =
        exec.putFileCalls().stream()
            .filter(c -> c.machineName().equals("m1"))
            .map(FakeRemoteExec.PutFileCall::localFile)
            .collect(Collectors.toSet());
    final Set<Path> m2Files =
        exec.putFileCalls().stream()
            .filter(c -> c.machineName().equals("m2"))
            .map(FakeRemoteExec.PutFileCall::localFile)
            .collect(Collectors.toSet());

    assertTrue(m1Files.contains(materialDir.resolve("ca.crt")));
    assertTrue(m1Files.contains(materialDir.resolve("controlplane-h1.crt")));
    assertTrue(m1Files.contains(materialDir.resolve("controlplane-h1.key")));
    assertTrue(m1Files.contains(materialDir.resolve("ca.key")), "m1 hosts the control plane");
    assertTrue(m1Files.contains(materialDir.resolve("fafnir-h1.crt")));
    assertTrue(m1Files.contains(keyFile), "m1 hosts fafnir, so it needs the fafnir key file");
    assertFalse(m1Files.contains(materialDir.resolve("muninn-h1.crt")), "m1 does not host muninn");

    assertTrue(m2Files.contains(materialDir.resolve("ca.crt")));
    assertTrue(m2Files.contains(materialDir.resolve("muninn-h2.crt")));
    assertFalse(
        m2Files.contains(materialDir.resolve("ca.key")), "m2 does not host the control plane");
    assertFalse(m2Files.contains(keyFile), "m2 does not host fafnir");
    assertFalse(
        m2Files.contains(materialDir.resolve("controlplane-h2.crt")),
        "m2 hosts neither a store nor a control plane");
  }

  @Test
  void upgrade_cluster_dispatches_the_new_classpath_and_roles_to_every_machine() {
    final Topology topology = topologyOf(List.of(bareMachine("m1", "h1")));
    final FakeRemoteExec exec = new FakeRemoteExec();

    RemoteDispatch.upgradeCluster(
        topology,
        Path.of("topology.yaml"),
        Optional.empty(),
        "/opt/gimle-0.2.0/lib/*",
        Optional.empty(),
        Optional.empty(),
        List.of("STORE", "FAFNIR"),
        SshCliFlags.NONE,
        exec,
        discardingOut());

    final List<String> command = exec.execCalls().get(0).command();
    assertTrue(command.contains("upgrade-cluster"));
    assertTrue(command.contains("--new-classpath"));
    assertTrue(command.contains("/opt/gimle-0.2.0/lib/*"));
    assertTrue(command.contains("--role"));
    assertTrue(command.contains("STORE"));
    assertTrue(command.contains("FAFNIR"));
  }
}
