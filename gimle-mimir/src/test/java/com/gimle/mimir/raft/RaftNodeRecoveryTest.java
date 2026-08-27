package com.gimle.mimir.raft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.store.StateStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The state machine holds nothing durable of its own, so a restarted node must rebuild it entirely
 * from the Raft log: the persisted snapshot restored at construction, plus committed entries
 * re-applied once the node's own election (and its fresh leader's no-op entry) re-advances the
 * commit index. These tests restart a single-node cluster against an empty {@link StateStore} and
 * assert the state comes back -- the recovery path a write-through store used to mask entirely.
 */
class RaftNodeRecoveryTest {

  @TempDir Path tempDir;

  private static DeploymentSpec deployment(String name) {
    return new DeploymentSpec(
        name,
        new ModuleId("com.gimle.example.orders", Version.parse("1.0.0")),
        "/var/gimle/artifacts/orders-1.0.0.jar",
        1,
        PlacementConstraints.NONE);
  }

  @Test
  void committed_writes_recover_into_an_empty_state_machine_after_restart() {
    Path dir = tempDir.resolve("raft");
    DeploymentSpec spec = deployment("orders-service");

    StateStore store = new StateStore();
    RaftNode node = new RaftNode("n1", Map.of(), new RaftLog(dir), store);
    node.start();
    node.propose(new StateMutation.PutDeployment(spec, 0));
    assertEquals(Optional.of(spec), store.getDeployment("orders-service"));
    node.close();

    // A fresh, empty state machine: everything it ends up holding must come from the log.
    StateStore restarted = new StateStore();
    RaftNode reopened = new RaftNode("n1", Map.of(), new RaftLog(dir), restarted);
    assertTrue(restarted.getDeployment("orders-service").isEmpty());
    // start() self-elects the single node, whose fresh-leader no-op entry lets it commit -- and
    // therefore re-apply -- its previous term's entries without waiting for a client write.
    reopened.start();
    assertEquals(Optional.of(spec), restarted.getDeployment("orders-service"));
    reopened.close();
  }

  @Test
  void a_persisted_snapshot_restores_the_state_machine_at_construction() {
    Path dir = tempDir.resolve("raft");
    DeploymentSpec spec = deployment("orders-service");

    StateStore source = new StateStore();
    source.putDeployment(spec);
    RaftLog log = new RaftLog(dir);
    log.installSnapshot(5, 1, RaftCodec.encodeSnapshot(source.snapshot()));
    log.close();

    StateStore restored = new StateStore();
    RaftNode node = new RaftNode("n1", Map.of(), new RaftLog(dir), restored);
    // Restored at construction, before start() -- the snapshot needs no election to apply.
    assertEquals(Optional.of(spec), restored.getDeployment("orders-service"));
    node.close();
  }

  @Test
  void a_second_restart_recovers_writes_from_both_prior_leaderships() {
    Path dir = tempDir.resolve("raft");

    StateStore first = new StateStore();
    RaftNode nodeA = new RaftNode("n1", Map.of(), new RaftLog(dir), first);
    nodeA.start();
    nodeA.propose(new StateMutation.PutDeployment(deployment("orders-service"), 0));
    nodeA.close();

    StateStore second = new StateStore();
    RaftNode nodeB = new RaftNode("n1", Map.of(), new RaftLog(dir), second);
    nodeB.start();
    nodeB.propose(new StateMutation.PutDeployment(deployment("catalog-service"), 0));
    nodeB.close();

    StateStore third = new StateStore();
    RaftNode nodeC = new RaftNode("n1", Map.of(), new RaftLog(dir), third);
    nodeC.start();
    assertEquals(2, third.listDeployments().size());
    nodeC.close();
  }
}
