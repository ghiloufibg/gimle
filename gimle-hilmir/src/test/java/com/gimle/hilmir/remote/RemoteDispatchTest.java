package com.gimle.hilmir.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.topology.AndvariRole;
import com.gimle.hilmir.topology.ControlPlaneRole;
import com.gimle.hilmir.topology.FafnirRole;
import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.MuninnRole;
import com.gimle.hilmir.topology.RuntimeSettings;
import com.gimle.hilmir.topology.StoreRole;
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

  private static Topology topologyOf(final List<Machine> machines) {
    return new Topology(
        "t",
        Transport.PLAINTEXT,
        Optional.empty(),
        machines,
        new RuntimeSettings(Optional.empty(), Optional.empty(), Optional.empty(), false),
        new StoreRole(List.of()),
        new ControlPlaneRole(List.of()),
        new FafnirRole(Optional.empty(), List.of()),
        new MuninnRole(List.of()),
        new AndvariRole(List.of()),
        List.of(),
        Map.of());
  }

  private static PrintStream discardingOut() {
    return new PrintStream(ByteArrayOutputStream.nullOutputStream());
  }

  @Test
  void up_with_no_machine_filter_dispatches_to_every_machine_in_the_topology() {
    final Topology topology = topologyOf(List.of(new Machine("m1", "h1"), new Machine("m2", "h2")));
    final FakeRemoteExec exec = new FakeRemoteExec();

    final int exitCode =
        RemoteDispatch.up(
            topology,
            Path.of("topology.yaml"),
            Optional.empty(),
            Optional.empty(),
            SshCliFlags.NONE,
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
    final Topology topology = topologyOf(List.of(new Machine("m1", "h1"), new Machine("m2", "h2")));
    final FakeRemoteExec exec = new FakeRemoteExec();

    RemoteDispatch.up(
        topology,
        Path.of("topology.yaml"),
        Optional.of("m1"),
        Optional.empty(),
        SshCliFlags.NONE,
        exec,
        discardingOut());

    assertEquals(1, exec.execCalls().size());
    assertEquals("m1", exec.execCalls().get(0).machineName());
  }

  @Test
  void up_with_an_unknown_machine_filter_throws() {
    final Topology topology = topologyOf(List.of(new Machine("m1", "h1")));
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
                exec,
                discardingOut()));
  }

  @Test
  void up_copies_the_topology_file_before_running_the_remote_up_command() {
    final Topology topology = topologyOf(List.of(new Machine("m1", "h1")));
    final FakeRemoteExec exec = new FakeRemoteExec();

    RemoteDispatch.up(
        topology,
        Path.of("topology.yaml"),
        Optional.empty(),
        Optional.empty(),
        SshCliFlags.NONE,
        exec,
        discardingOut());

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
    final Topology topology = topologyOf(List.of(new Machine("m1", "h1")));
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
    final Topology topology = topologyOf(List.of(new Machine("m1", "h1")));
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
    final Topology topology = topologyOf(List.of(new Machine("m1", "h1"), new Machine("m2", "h2")));
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
    final Topology topology = topologyOf(List.of(new Machine("m1", "h1"), new Machine("m2", "h2")));
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
    final Topology topology = topologyOf(List.of(new Machine("m1", "h1"), new Machine("m2", "h2")));
    final FakeRemoteExec exec = new FakeRemoteExec();

    final int exitCode =
        RemoteDispatch.status(
            topology, Optional.empty(), Optional.empty(), SshCliFlags.NONE, exec, discardingOut());

    assertEquals(0, exitCode);
  }
}
