package com.gimle.ragnarok.target.inventory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.topology.Machine;
import com.gimle.ragnarok.RagnarokException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers what's cheap to test without a real SSH round trip: {@code faults()}'s presence decision
 * and {@code requireStorePartitionSupport()}'s eager validation. End-to-end firing/healing is
 * covered by {@link SshNetworkFaultInjectorTest} and the real-SSH integration test.
 */
final class SshInventoryClusterTargetTest {

  @TempDir private Path workDir;

  private static Machine machine(final String name) {
    return new Machine(name, name + ".example", Optional.empty(), Optional.empty());
  }

  private static ManagedRoleSpec storeRole(final String id, final Optional<Integer> raftPort) {
    return new ManagedRoleSpec(
        "m1",
        id,
        Path.of("/a/" + id + ".pid"),
        Path.of("/a/" + id + ".log"),
        List.of("java"),
        raftPort);
  }

  private SshInventoryClusterTarget target(final InventorySpec inventory) {
    return new SshInventoryClusterTarget(
        List.of("http://localhost:1"),
        HttpClient.newHttpClient(),
        List.of(),
        List.of(),
        List.of(),
        workDir,
        inventory);
  }

  @Test
  void faults_is_present_once_any_store_or_control_plane_role_is_declared() {
    final InventorySpec inventory =
        new InventorySpec(
            List.of(machine("m1")),
            List.of(storeRole("store-0", Optional.empty())),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            false);
    assertTrue(target(inventory).faults().isPresent());
  }

  @Test
  void faults_is_absent_when_nothing_is_declared_to_strike() {
    final InventorySpec inventory =
        new InventorySpec(
            List.of(machine("m1")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            false);
    assertTrue(target(inventory).faults().isEmpty());
  }

  @Test
  void require_store_partition_support_passes_when_every_store_has_a_raft_port() {
    final InventorySpec inventory =
        new InventorySpec(
            List.of(machine("m1")),
            List.of(
                storeRole("store-0", Optional.of(9080)), storeRole("store-1", Optional.of(9081))),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            false);
    target(inventory).requireStorePartitionSupport(); // must not throw
  }

  @Test
  void require_store_partition_support_names_every_store_missing_a_raft_port() {
    final InventorySpec inventory =
        new InventorySpec(
            List.of(machine("m1")),
            List.of(
                storeRole("store-0", Optional.empty()), storeRole("store-1", Optional.of(9081))),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            false);
    final RagnarokException e =
        assertThrows(
            RagnarokException.class, () -> target(inventory).requireStorePartitionSupport());
    assertTrue(e.getMessage().contains("store-0"));
    assertFalse(e.getMessage().contains("store-1"));
  }
}
