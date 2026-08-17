package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.cluster.ClusterApi;
import com.gimle.holmgang.topology.QuotaSpec;
import com.gimle.holmgang.workload.RecordingWorkload;
import com.gimle.testkit.Await;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Steps running a recorded write workload and asserting its durability promises were kept. */
public final class WorkloadSteps {

  private final ScenarioWorld world;

  public WorkloadSteps(final ScenarioWorld world) {
    this.world = world;
  }

  @Given("a background writer creating tenants every {int}ms")
  public void aBackgroundWriterCreatingTenants(final int intervalMillis) {
    world.workload =
        RecordingWorkload.tenantWriter(world.cluster().api(), Duration.ofMillis(intervalMillis));
  }

  /**
   * The store cluster can transiently reject writes mid-election after a member's death; this is
   * the explicit "a new leader is serving again" gate before any step that must not flake on a 503.
   */
  @Then("within {int}s the cluster accepts writes again")
  public void theClusterAcceptsWritesAgain(final int seconds) {
    world
        .cluster()
        .when()
        .probe(
            "the cluster accepts writes again",
            () ->
                world.cluster().api().tryPutTenant("write-probe-tenant", QuotaSpec.of(1024, 10, 1))
                    == 200)
        .await(Duration.ofSeconds(seconds));
  }

  /**
   * The explicit "the workload has really banked promises" gate: without a minimum acknowledged
   * count before a fault (and again after recovery), a fast kill could race the writer's first
   * successful write and the durability assertion would vacuously pass over an empty history.
   */
  @Then("within {int}s the writer has acknowledged at least {int} writes")
  public void theWriterHasAcknowledgedAtLeastWrites(final int seconds, final int count) {
    if (world.workload == null) {
      throw new HolmgangException("no background writer was started in this scenario");
    }
    world
        .cluster()
        .when()
        .probe(
            "the writer has acknowledged at least " + count + " writes",
            () -> world.workload.acknowledgedCount() >= count)
        .await(Duration.ofSeconds(seconds));
  }

  /**
   * Submitted on a background thread rather than inline: the whole point of this step is to prove
   * the call itself never hangs, which a step blocking directly on {@code tryPutTenant} could not
   * demonstrate -- a genuine indefinite hang would just hang the step (and the whole scenario)
   * right along with it instead of failing it.
   */
  @When("a tenant write is submitted against the cluster")
  public void aTenantWriteIsSubmittedAgainstTheCluster() {
    final ClusterApi api = world.cluster().api();
    final String tenantId = "partition-write-probe-" + System.nanoTime();
    world.pendingWrite =
        CompletableFuture.supplyAsync(() -> api.tryPutTenant(tenantId, QuotaSpec.of(1024, 10, 1)));
  }

  /**
   * Passes on either outcome the write can reach on its own -- a definite HTTP status (200 via the
   * newly-elected reachable leader, or a rejection) or a thrown exception -- and fails only on the
   * one outcome that matters here: the call still being in flight once {@code seconds} elapses,
   * which is exactly the indefinite hang the store transport's connect/read timeouts exist to rule
   * out.
   */
  @Then("the submitted write completes within {int}s instead of hanging")
  public void theSubmittedWriteCompletesWithinSecondsInsteadOfHanging(final int seconds) {
    if (world.pendingWrite == null) {
      throw new HolmgangException("no write was submitted in this scenario");
    }
    try {
      world.pendingWrite.get(seconds, TimeUnit.SECONDS);
    } catch (final TimeoutException e) {
      throw new HolmgangException(
          "the submitted write did not complete within "
              + seconds
              + "s -- it hung instead of succeeding or failing");
    } catch (final ExecutionException e) {
      // A thrown client-side failure (e.g. every endpoint refused) is still a bounded, definite
      // outcome -- exactly as acceptable here as a non-200 HTTP status.
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HolmgangException("interrupted while awaiting the submitted write", e);
    }
  }

  /**
   * The negative counterpart to {@link #theClusterAcceptsWritesAgain}: spin-polls the same real
   * write path for the whole window (via {@link Await}, this codebase's one shared no-fixed-sleep
   * primitive) and fails the instant any attempt succeeds -- what proves a learner genuinely never
   * counts toward quorum (only its voting peers do), rather than merely "eventually some write got
   * through." {@link Await#until} always throws once its own timeout elapses without the condition
   * turning true; here that timeout is exactly the success case, so it is caught and swallowed,
   * while a write actually succeeding propagates as the real, distinct failure.
   */
  @Then("the cluster does not accept writes for {int}s")
  public void theClusterDoesNotAcceptWritesFor(final int seconds) {
    try {
      Await.until(
          () -> {
            final int status =
                world
                    .cluster()
                    .api()
                    .tryPutTenant(
                        "no-quorum-probe-" + System.nanoTime(), QuotaSpec.of(1024, 10, 1));
            if (status == 200) {
              throw new HolmgangException(
                  "a write unexpectedly succeeded (status 200) while quorum should not be"
                      + " reachable");
            }
            return false;
          },
          Duration.ofSeconds(seconds));
    } catch (final HolmgangException e) {
      throw e;
    } catch (final AssertionError expectedTimeout) {
      // Await's own "never became true within the window" -- exactly what "still refuses writes"
      // means for this negative assertion.
    }
  }

  @Then("every acknowledged tenant write is readable")
  public void everyAcknowledgedTenantWriteIsReadable() {
    if (world.workload == null) {
      throw new HolmgangException("no background writer was started in this scenario");
    }
    final List<String> acknowledged = world.workload.stopAndAcknowledged();
    assertTrue(
        !acknowledged.isEmpty(),
        "the background writer never got a single acknowledged write -- the workload proves"
            + " nothing");
    final List<String> lost = new ArrayList<>();
    for (final String tenantId : acknowledged) {
      if (!world.cluster().api().tenantExists(tenantId)) {
        lost.add(tenantId);
      }
    }
    assertTrue(
        lost.isEmpty(),
        "acknowledged writes lost after the fault: "
            + lost
            + " (of "
            + acknowledged.size()
            + " acknowledged)");
  }
}
