package com.gimle.hilmir.launch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.plan.ClusterPlan;
import com.gimle.hilmir.plan.MachinePlan;
import com.gimle.hilmir.plan.ProcessCommand;
import com.gimle.hilmir.topology.ProcessRole;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link MachineLauncher#requireStoreQuorumMaintained} directly, against a synthetic
 * {@link ClusterPlan} of store {@link ProcessCommand}s whose readiness addresses point either at a
 * real bound loopback socket (reachable) or a definitely-free port (unreachable) -- real TCP
 * reachability, the same signal {@link ReadinessPoller#isPortOpen} always checks, without needing
 * to spawn an actual store process for a gating decision that only cares about port reachability.
 */
class MachineLauncherStoreQuorumGateTest {

  private static ProcessCommand storeCommand(
      final String id, final String machine, final String readiness) {
    return new ProcessCommand(
        ProcessRole.STORE,
        id,
        machine,
        List.of("java", "-version"),
        id + ".log",
        Path.of("/unused"),
        readiness,
        false);
  }

  private static ClusterPlan planOf(final ProcessCommand... commands) {
    final Map<String, MachinePlan> byMachine = new LinkedHashMap<>();
    for (final ProcessCommand command : commands) {
      byMachine.put(command.machine(), new MachinePlan(command.machine(), List.of(command)));
    }
    return new ClusterPlan(byMachine);
  }

  @Test
  void refuses_when_fewer_than_a_majority_of_the_other_store_replicas_are_reachable()
      throws IOException {
    // Three stores total; majority of 3 is 2. Neither of the other two is reachable here.
    final ProcessCommand restarting = storeCommand("store-0", "m1", "127.0.0.1:1");
    final ProcessCommand other1 = storeCommand("store-1", "m2", unreachableAddress());
    final ProcessCommand other2 = storeCommand("store-2", "m3", unreachableAddress());
    final ClusterPlan plan = planOf(restarting, other1, other2);

    final HilmirException e =
        assertThrows(
            HilmirException.class,
            () -> MachineLauncher.requireStoreQuorumMaintained(plan, restarting, "before"));

    assertTrue(e.getMessage().contains("quorum"));
    assertTrue(e.getMessage().contains("store-0"));
  }

  @Test
  void permits_when_a_majority_of_the_other_store_replicas_are_reachable() throws IOException {
    try (ServerSocket socket1 = boundSocket();
        ServerSocket socket2 = boundSocket()) {
      final ProcessCommand restarting = storeCommand("store-0", "m1", "127.0.0.1:1");
      final ProcessCommand other1 =
          storeCommand("store-1", "m2", "127.0.0.1:" + socket1.getLocalPort());
      final ProcessCommand other2 =
          storeCommand("store-2", "m3", "127.0.0.1:" + socket2.getLocalPort());
      final ClusterPlan plan = planOf(restarting, other1, other2);

      assertDoesNotThrow(
          () -> MachineLauncher.requireStoreQuorumMaintained(plan, restarting, "before"));
    }
  }

  @Test
  void a_single_replica_store_has_no_quorum_to_protect_and_is_always_permitted() {
    final ProcessCommand onlyStore = storeCommand("store-0", "m1", "127.0.0.1:1");
    final ClusterPlan plan = planOf(onlyStore);

    assertDoesNotThrow(
        () -> MachineLauncher.requireStoreQuorumMaintained(plan, onlyStore, "before"));
  }

  private static ServerSocket boundSocket() throws IOException {
    return new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
  }

  /** A loopback address definitely not accepting connections at the moment of the call. */
  private static String unreachableAddress() throws IOException {
    return "127.0.0.1:" + LaunchTestSupport.freePort();
  }
}
