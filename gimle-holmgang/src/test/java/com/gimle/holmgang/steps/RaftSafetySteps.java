package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.holmgang.HolmgangException;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.rpc.StoreClient;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Steps exercising Raft's own internal safety mechanics directly against the real {@link
 * StoreClient} production API -- log compaction/snapshotting and a leader's own ghost-write
 * prevention -- rather than only observing their downstream effects the way {@link WorkloadSteps}
 * and {@link FaultSteps} already do for the coarser member/leader-death scenarios.
 */
public final class RaftSafetySteps {

  private final ScenarioWorld world;

  public RaftSafetySteps(final ScenarioWorld world) {
    this.world = world;
  }

  /**
   * Proposes {@code count} distinct tenants one at a time through any live store, forcing the
   * leader's own log past {@code RaftNode}'s compaction threshold so a subsequently-joining learner
   * can only catch up via a chunked {@code InstallSnapshot} transfer, never plain log replay.
   */
  @When("{int} tenants are written directly to the store")
  public void tenantsAreWrittenDirectlyToTheStore(final int count) {
    try (StoreClient client = world.cluster().openStoreClient()) {
      for (int i = 0; i < count; i++) {
        client.propose(
            new StateMutation.PutTenant(
                new Tenant("bulk-compaction-" + i, new ResourceQuota(1024, 10, 1))));
      }
    }
  }

  /**
   * Proposes a tenant write on a background thread, dialed directly at the currently-isolated
   * leader's own client port (see {@link FaultSteps#theStoreLeaderIsPartitionedFromItsPeers}) --
   * not the pooled, any-endpoint client {@link ScenarioWorld#cluster()}'s {@code api()} uses, which
   * could resolve to a different, still-reachable replica instead. This is the one way to guarantee
   * the proposal actually reaches the self-believing-leader whose own ghost-write prevention is
   * under test.
   */
  @When("a tenant write is proposed directly to the isolated leader")
  public void aTenantWriteIsProposedDirectlyToTheIsolatedLeader() {
    if (world.isolatedStoreIndex == null) {
      throw new HolmgangException("no store leader was partitioned in this scenario");
    }
    final int index = world.isolatedStoreIndex;
    final String tenantId = "ghost-write-" + System.nanoTime();
    world.ghostWriteTenantId = tenantId;
    world.ghostWriteOutcome =
        CompletableFuture.runAsync(
            () -> {
              try (StoreClient client = world.cluster().openStoreClient(index)) {
                client.propose(
                    new StateMutation.PutTenant(
                        new Tenant(tenantId, new ResourceQuota(1024, 10, 1))));
              }
            });
  }

  /**
   * The propose-timeout half of ghost-write prevention: the isolated leader can never reach a
   * majority ack for this proposal, so it must give up and refuse it -- not hang forever -- within
   * its own propose timeout.
   */
  @Then("the proposal to the isolated leader is refused within {int}s")
  public void theProposalToTheIsolatedLeaderIsRefusedWithinSeconds(final int seconds) {
    if (world.ghostWriteOutcome == null) {
      throw new HolmgangException(
          "no proposal was submitted to an isolated leader in this scenario");
    }
    try {
      world.ghostWriteOutcome.get(seconds, TimeUnit.SECONDS);
      throw new HolmgangException(
          "the isolated leader's proposal unexpectedly succeeded -- ghost-write prevention should"
              + " have refused it");
    } catch (final ExecutionException expected) {
      // The real, expected outcome: propose() threw because majority ack never arrived.
    } catch (final TimeoutException e) {
      throw new HolmgangException(
          "the proposal to the isolated leader neither succeeded nor failed within "
              + seconds
              + "s -- it hung instead of being refused");
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HolmgangException("interrupted while awaiting the isolated leader's proposal", e);
    }
  }

  /** The truncation half: once refused, the entry must never resurface as committed data. */
  @Then("the ghost-written tenant is never readable")
  public void theGhostWrittenTenantIsNeverReadable() {
    if (world.ghostWriteTenantId == null) {
      throw new HolmgangException("no ghost-write tenant id was recorded in this scenario");
    }
    assertFalse(
        world.cluster().api().tenantExists(world.ghostWriteTenantId),
        "tenant "
            + world.ghostWriteTenantId
            + " is readable -- a ghost write survived instead of being truncated");
  }
}
