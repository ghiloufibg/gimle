package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.fenrir.ChaosLedger;
import com.gimle.holmgang.fenrir.Fenrir;
import com.gimle.holmgang.fenrir.FenrirPlan;
import com.gimle.holmgang.fenrir.Pools;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;

/**
 * Steps that unleash Fenrir -- a randomized, converge-then-strike fault soak -- and assert on its
 * ledger. A soak may only run on a {@code @destructive} scenario: it owns a fresh cluster outright,
 * because repeated kills and bounces leave no clean state for a pooled cluster to be handed back
 * in.
 */
public final class ChaosSteps {

  /**
   * A fixed default seed so runs reproduce; overridable per run through {@link
   * FenrirPlan#SEED_PROPERTY} for replay or variation.
   */
  private static final long DEFAULT_SEED = 0xF00DL;

  private final ScenarioWorld world;

  public ChaosSteps(final ScenarioWorld world) {
    this.world = world;
  }

  @When("Fenrir is unleashed for {int} seconds striking every {int} seconds")
  public void fenrirIsUnleashed(final int soakSeconds, final int gapSeconds) {
    if (!world.isDestructive()) {
      throw new HolmgangException(
          "a Fenrir soak must run on a @destructive scenario -- it needs a fresh, owned cluster");
    }
    final String[] eligible = world.deployments.keySet().toArray(new String[0]);
    final FenrirPlan.Builder plan =
        FenrirPlan.seeded(DEFAULT_SEED)
            .soakFor(Duration.ofSeconds(soakSeconds))
            .strikeEvery(Duration.ofSeconds(gapSeconds))
            .pool(Pools.storeBounces())
            .pool(Pools.leaderBounces())
            .pool(Pools.controlPlaneBounces());
    if (eligible.length > 0) {
      plan.eligibleDeployments(eligible).pool(Pools.workerKills().weight(2));
    }
    final ChaosLedger ledger = Fenrir.unleash(world.cluster(), plan.build());
    world.chaosLedger = ledger;
    // The test JVM's own stdout -- the ledger summary belongs in the scenario log on success, and
    // is the first thing to read on failure.
    System.out.println(ledger.render());
  }

  @Then("the chaos ledger shows at least {int} executed faults")
  public void theChaosLedgerShowsAtLeastExecutedFaults(final int count) {
    final ChaosLedger ledger = ledger();
    assertTrue(
        ledger.executedCount() >= count,
        "expected at least "
            + count
            + " executed faults but got "
            + ledger.executedCount()
            + "\n"
            + ledger.render());
  }

  @Then("every executed fault recovered")
  public void everyExecutedFaultRecovered() {
    final ChaosLedger ledger = ledger();
    assertTrue(
        ledger.allRecovered(), "a fault did not recover within its gate\n" + ledger.render());
  }

  private ChaosLedger ledger() {
    if (world.chaosLedger == null) {
      throw new HolmgangException("no Fenrir soak has run in this scenario");
    }
    return world.chaosLedger;
  }
}
