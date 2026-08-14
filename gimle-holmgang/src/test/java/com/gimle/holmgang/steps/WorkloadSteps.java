package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.topology.QuotaSpec;
import com.gimle.holmgang.workload.RecordingWorkload;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
