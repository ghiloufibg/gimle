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
    unleash(basePlan(soakSeconds, gapSeconds), soakSeconds);
  }

  /**
   * The compound-fault variant: strikes keep firing on the gap cadence even when the previous one's
   * own recovery gate hasn't cleared, instead of the soak halting on the first missed gate (see
   * {@link FenrirPlan.Builder#convergeBetweenFaults}). A tightened recovery gate makes this
   * realistic within a short soak window -- a gate shorter than the platform's own reconcile
   * interval-driven recovery time means a still-recovering victim can genuinely be caught mid-
   * recovery by the next scheduled strike.
   */
  @When(
      "Fenrir is unleashed in compound-fault mode for {int} seconds striking every {int} seconds"
          + " with a {int}-second recovery gate")
  public void fenrirIsUnleashedInCompoundFaultMode(
      final int soakSeconds, final int gapSeconds, final int gateSeconds) {
    unleash(
        basePlan(soakSeconds, gapSeconds)
            .gateTimeout(Duration.ofSeconds(gateSeconds))
            .convergeBetweenFaults(false),
        soakSeconds);
  }

  /**
   * The narrow variant exercising only the two replica-fan-out/peer-sync bounces, for a topology
   * built specifically with multiple Muninn and Andvari replicas -- mixing these in with the full
   * {@link #basePlan}'s store/leader/control-plane/Fafnir/worker-kill palette would dilute a soak
   * meant to prove those two faults specifically recover, not the whole palette at once.
   */
  @When(
      "Fenrir is unleashed striking only muninn and andvari bounces for {int} seconds every"
          + " {int} seconds")
  public void fenrirIsUnleashedStrikingOnlyMuninnAndAndvariBounces(
      final int soakSeconds, final int gapSeconds) {
    requireDestructive();
    unleash(
        FenrirPlan.seeded(DEFAULT_SEED)
            .soakFor(Duration.ofSeconds(soakSeconds))
            .strikeEvery(Duration.ofSeconds(gapSeconds))
            .pool(Pools.muninnBounces())
            .pool(Pools.andvariBounces()),
        soakSeconds);
  }

  /** The pools and eligibility every soak mode shares; each caller layers its own mode knobs on. */
  private FenrirPlan.Builder basePlan(final int soakSeconds, final int gapSeconds) {
    requireDestructive();
    final String[] eligible = world.deployments.keySet().toArray(new String[0]);
    final FenrirPlan.Builder plan =
        FenrirPlan.seeded(DEFAULT_SEED)
            .soakFor(Duration.ofSeconds(soakSeconds))
            .strikeEvery(Duration.ofSeconds(gapSeconds))
            .pool(Pools.storeBounces())
            .pool(Pools.leaderBounces())
            .pool(Pools.controlPlaneBounces())
            .pool(Pools.fafnirBounces());
    if (eligible.length > 0) {
      plan.eligibleDeployments(eligible).pool(Pools.workerKills().weight(2));
    }
    // Only meaningful -- and only ever drawn from, per Fenrir's own replica floor -- on a topology
    // that actually requested more than one replica; a plain single-replica topology leaves these
    // pools out entirely rather than building one that could only ever skip.
    if (world.cluster().muninnCount() > 1) {
      plan.pool(Pools.muninnBounces());
    }
    if (world.cluster().andvariCount() > 1) {
      plan.pool(Pools.andvariBounces());
    }
    return plan;
  }

  private void requireDestructive() {
    if (!world.isDestructive()) {
      throw new HolmgangException(
          "a Fenrir soak must run on a @destructive scenario -- it needs a fresh, owned cluster");
    }
  }

  private void unleash(final FenrirPlan.Builder plan, final int soakSeconds) {
    final ChaosLedger ledger = Fenrir.unleash(world.cluster(), plan.build());
    world.chaosLedger = ledger;
    com.gimle.holmgang.saga.SagaCollector.instance()
        .recordFenrir(world.scenarioName, ledger, Duration.ofSeconds(soakSeconds).toMillis());
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
