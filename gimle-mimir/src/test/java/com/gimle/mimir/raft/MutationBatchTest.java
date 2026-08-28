package com.gimle.mimir.raft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.store.StateStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MutationBatchTest {

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
  void an_empty_batch_is_rejected() {
    assertThrows(IllegalArgumentException.class, () -> new StateMutation.Batch(List.of()));
  }

  @Test
  void a_nested_batch_is_rejected() {
    StateMutation.Batch inner =
        new StateMutation.Batch(List.of(new StateMutation.RemoveDeployment("orders-service", 0)));
    assertThrows(IllegalArgumentException.class, () -> new StateMutation.Batch(List.of(inner)));
  }

  @Test
  void a_batch_applies_its_mutations_in_order() {
    StateStore store = new StateStore();
    // Put then remove the same key: only in-order application leaves the store empty.
    new StateMutation.Batch(
            List.of(
                new StateMutation.PutDeployment(deployment("orders-service"), 0),
                new StateMutation.PutDeployment(deployment("catalog-service"), 0),
                new StateMutation.RemoveDeployment("orders-service", 1)))
        .applyTo(store);

    assertTrue(store.getDeployment("orders-service").isEmpty());
    assertEquals(
        Optional.of(deployment("catalog-service")), store.getDeployment("catalog-service"));
  }

  @Test
  void propose_all_of_an_empty_list_proposes_nothing() {
    List<StateMutation> proposed = new ArrayList<>();
    MutationSink sink =
        m -> {
          proposed.add(m);
          return MutationOutcome.accepted();
        };

    sink.proposeAll(List.of());

    assertTrue(proposed.isEmpty());
  }

  @Test
  void propose_all_of_a_single_mutation_proposes_it_bare_not_wrapped() {
    List<StateMutation> proposed = new ArrayList<>();
    MutationSink sink =
        m -> {
          proposed.add(m);
          return MutationOutcome.accepted();
        };
    StateMutation only = new StateMutation.RemoveDeployment("orders-service", 0);

    sink.proposeAll(List.of(only));

    assertEquals(List.of(only), proposed);
  }

  @Test
  void propose_all_of_several_mutations_proposes_one_batch_carrying_them_in_order() {
    List<StateMutation> proposed = new ArrayList<>();
    MutationSink sink =
        m -> {
          proposed.add(m);
          return MutationOutcome.accepted();
        };
    List<StateMutation> burst =
        List.of(
            new StateMutation.PutDeployment(deployment("orders-service"), 0),
            new StateMutation.RemoveDeployment("catalog-service", 0));

    sink.proposeAll(burst);

    assertEquals(1, proposed.size());
    assertEquals(new StateMutation.Batch(burst), proposed.getFirst());
  }

  @Test
  void a_batched_proposal_is_one_log_entry_and_applies_every_mutation() {
    StateStore store = new StateStore();
    RaftLog raftLog = new RaftLog(tempDir.resolve("raft"));
    RaftNode node = new RaftNode("n1", Map.of(), raftLog, store);
    node.start();
    long indexBefore = raftLog.lastIndex();

    node.proposeAll(
        List.of(
            new StateMutation.PutDeployment(deployment("orders-service"), 0),
            new StateMutation.PutDeployment(deployment("catalog-service"), 0)));

    assertEquals(indexBefore + 1, raftLog.lastIndex());
    assertEquals(Optional.of(deployment("orders-service")), store.getDeployment("orders-service"));
    assertEquals(
        Optional.of(deployment("catalog-service")), store.getDeployment("catalog-service"));
    node.close();
  }
}
