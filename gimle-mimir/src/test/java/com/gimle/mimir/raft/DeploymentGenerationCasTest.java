package com.gimle.mimir.raft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.store.StateStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The generation counter's one job beyond ordering concurrent writers: telling a name that was
 * deleted apart from one that was never used. An entry carries the generation its proposer read at
 * propose time and has its precondition checked only when it applies, and the two can be minutes
 * apart -- an entry a leader appended without committing survives that leader's demotion in a
 * follower's log and is committed by whichever leader comes next.
 */
class DeploymentGenerationCasTest {

  private static final Optional<String> NO_TENANT = Optional.empty();

  private static DeploymentSpec deployment() {
    return new DeploymentSpec(
        "orders-service",
        new ModuleId("com.gimle.example.orders", Version.parse("1.0.0")),
        "/var/gimle/artifacts/orders-1.0.0.jar",
        1,
        PlacementConstraints.NONE);
  }

  private static long generation(StateStore store) {
    return store.getDeploymentGeneration(NO_TENANT, "orders-service");
  }

  private static void createThenDelete(StateStore store) {
    new StateMutation.PutDeployment(deployment(), generation(store)).applyTo(store);
    new StateMutation.RemoveDeployment(NO_TENANT, "orders-service", generation(store))
        .applyTo(store);
  }

  @Test
  void an_entry_proposed_while_the_name_was_free_cannot_recreate_it_after_a_delete() {
    StateStore store = new StateStore();
    // What a proposer reads for a name nothing has ever used. Its entry is appended here but not
    // committed, so nothing has applied yet.
    long capturedWhileFree = generation(store);

    createThenDelete(store);
    assertTrue(store.getDeployment(NO_TENANT, "orders-service").isEmpty());

    // Only now does the entry captured back when the name was free reach the state machine.
    MutationOutcome outcome =
        new StateMutation.PutDeployment(deployment(), capturedWhileFree).applyTo(store);

    assertInstanceOf(MutationOutcome.Rejected.class, outcome);
    assertTrue(
        store.getDeployment(NO_TENANT, "orders-service").isEmpty(),
        "an acknowledged delete must stay durable against a proposal older than it");
  }

  @Test
  void a_delete_leaves_a_generation_no_later_proposal_can_read_as_a_free_name() {
    StateStore store = new StateStore();
    assertEquals(0L, generation(store), "a name nothing has used reads as generation 0");

    createThenDelete(store);

    assertTrue(generation(store) > 0L, "a deleted name must never read back as a free one");
  }

  @Test
  void an_apply_that_genuinely_follows_the_delete_recreates_the_deployment() {
    StateStore store = new StateStore();
    createThenDelete(store);

    // The generation this proposer reads is the one the delete left behind, not the 0 the name
    // started at -- a real recreate is unaffected by the tombstone the test above depends on.
    MutationOutcome outcome =
        new StateMutation.PutDeployment(deployment(), generation(store)).applyTo(store);

    assertEquals(MutationOutcome.accepted(), outcome);
    assertEquals(Optional.of(deployment()), store.getDeployment(NO_TENANT, "orders-service"));
  }
}
