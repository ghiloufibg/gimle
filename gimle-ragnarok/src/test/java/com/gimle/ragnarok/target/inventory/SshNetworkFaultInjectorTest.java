package com.gimle.ragnarok.target.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.topology.Machine;
import com.gimle.ragnarok.RagnarokException;
import com.gimle.ragnarok.target.NetworkFaultInjector;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link SshNetworkFaultInjector} against a {@link RecordingRemoteExec} -- asserting on
 * the exact {@code iptables} argv it builds, never actually running anything (unlike {@link
 * FakeRemoteExec}, which would attempt to mutate the test runner's own firewall).
 */
final class SshNetworkFaultInjectorTest {

  private static final List<String> COMMENT_SUFFIX =
      List.of("-m", "comment", "--comment", "ragnarok-fault");

  private static Machine machine(final String name) {
    return new Machine(name, name + ".example", Optional.empty(), Optional.empty());
  }

  private static ManagedRoleSpec role(
      final String machine, final String id, final Optional<Integer> raftPort) {
    return new ManagedRoleSpec(
        machine,
        id,
        Path.of("/a/" + id + ".pid"),
        Path.of("/a/" + id + ".log"),
        List.of("java"),
        raftPort);
  }

  private static InventorySpec inventory(
      final List<ManagedRoleSpec> store, final ManagedRoleSpec controlPlane, final boolean sudo) {
    return new InventorySpec(
        List.of(machine("cp-machine"), machine("store-a"), machine("store-b"), machine("store-c")),
        store,
        controlPlane == null ? List.of() : List.of(controlPlane),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        sudo);
  }

  private static SshNetworkFaultInjector injector(
      final RecordingRemoteExec exec,
      final InventorySpec inventory,
      final SocketAddress... stores) {
    return new SshNetworkFaultInjector(exec, inventory, List.of(stores), m -> {});
  }

  @Test
  void cut_control_plane_from_stores_inserts_one_reject_rule_per_store() {
    final RecordingRemoteExec exec = new RecordingRemoteExec();
    final ManagedRoleSpec cp = role("cp-machine", "controlplane-0", Optional.empty());
    final InventorySpec inventory = inventory(List.of(), cp, false);
    final SshNetworkFaultInjector fault =
        injector(
            exec,
            inventory,
            new InetSocketAddress("store-a.example", 7101),
            new InetSocketAddress("store-b.example", 7101));

    fault.cutControlPlaneFromStores(0);

    final List<RecordingRemoteExec.Call> calls = exec.calls();
    assertEquals(2, calls.size());
    assertEquals(
        List.of(
            "iptables",
            "-I",
            "OUTPUT",
            "-d",
            "store-a.example",
            "-p",
            "tcp",
            "--dport",
            "7101",
            "-j",
            "REJECT",
            "--reject-with",
            "tcp-reset",
            "-m",
            "comment",
            "--comment",
            "ragnarok-fault"),
        calls.get(0).command());
    assertEquals("cp-machine", calls.get(0).machineName());
    assertTrue(calls.get(1).command().contains("store-b.example"));
  }

  @Test
  void cut_control_plane_from_stores_prefixes_sudo_when_configured() {
    final RecordingRemoteExec exec = new RecordingRemoteExec();
    final ManagedRoleSpec cp = role("cp-machine", "controlplane-0", Optional.empty());
    final InventorySpec inventory = inventory(List.of(), cp, true);
    final SshNetworkFaultInjector fault =
        injector(exec, inventory, new InetSocketAddress("store-a.example", 7101));

    fault.cutControlPlaneFromStores(0);

    assertEquals(List.of("sudo", "-n", "iptables"), exec.calls().get(0).command().subList(0, 3));
  }

  @Test
  void cut_store_from_peers_inserts_bidirectional_drop_rules_against_every_other_store() {
    final RecordingRemoteExec exec = new RecordingRemoteExec();
    final List<ManagedRoleSpec> stores =
        List.of(
            role("store-a", "store-0", Optional.of(9080)),
            role("store-b", "store-1", Optional.of(9081)),
            role("store-c", "store-2", Optional.of(9082)));
    final InventorySpec inventory = inventory(stores, null, false);
    final SshNetworkFaultInjector fault = injector(exec, inventory);

    fault.cutStoreFromPeers(0);

    final List<RecordingRemoteExec.Call> calls = exec.calls();
    // Two peers (store-1, store-2), two rules each (OUTPUT + INPUT) = 4 calls.
    assertEquals(4, calls.size());
    assertEquals("store-a", calls.get(0).machineName());
    assertEquals(
        List.of(
            "iptables",
            "-I",
            "OUTPUT",
            "-d",
            "store-b.example",
            "-p",
            "tcp",
            "--dport",
            "9081",
            "-j",
            "DROP"),
        calls.get(0).command().subList(0, 11));
    assertEquals(
        List.of(
            "iptables",
            "-I",
            "INPUT",
            "-s",
            "store-b.example",
            "-p",
            "tcp",
            "--dport",
            "9080",
            "-j",
            "DROP"),
        calls.get(1).command().subList(0, 11));
    assertTrue(calls.get(0).command().containsAll(COMMENT_SUFFIX));
  }

  @Test
  void cut_store_from_peers_throws_when_the_victim_has_no_raft_port() {
    final List<ManagedRoleSpec> stores =
        List.of(
            role("store-a", "store-0", Optional.empty()),
            role("store-b", "store-1", Optional.of(9081)));
    final InventorySpec inventory = inventory(stores, null, false);
    final SshNetworkFaultInjector fault = injector(new RecordingRemoteExec(), inventory);

    assertThrows(RagnarokException.class, () -> fault.cutStoreFromPeers(0));
  }

  @Test
  void cut_store_from_peers_throws_when_a_peer_has_no_raft_port() {
    final List<ManagedRoleSpec> stores =
        List.of(
            role("store-a", "store-0", Optional.of(9080)),
            role("store-b", "store-1", Optional.empty()));
    final InventorySpec inventory = inventory(stores, null, false);
    final SshNetworkFaultInjector fault = injector(new RecordingRemoteExec(), inventory);

    assertThrows(RagnarokException.class, () -> fault.cutStoreFromPeers(0));
  }

  @Test
  void heal_replays_every_inserted_rule_as_a_delete() {
    final RecordingRemoteExec exec = new RecordingRemoteExec();
    final ManagedRoleSpec cp = role("cp-machine", "controlplane-0", Optional.empty());
    final InventorySpec inventory = inventory(List.of(), cp, false);
    final SshNetworkFaultInjector fault =
        injector(exec, inventory, new InetSocketAddress("store-a.example", 7101));
    final NetworkFaultInjector.Partition partition = fault.cutControlPlaneFromStores(0);
    exec.clearCalls(); // discard the insert calls; only the heal round trip matters below.

    partition.heal();

    final List<RecordingRemoteExec.Call> healCalls = exec.calls();
    assertEquals(1, healCalls.size());
    assertEquals(List.of("sh", "-c"), healCalls.get(0).command().subList(0, 2));
    final String script = healCalls.get(0).command().get(2);
    assertTrue(script.contains("-D"));
    assertTrue(script.contains("OUTPUT"));
    assertTrue(script.contains("store-a.example"));
    assertTrue(script.contains("7101"));
  }
}
