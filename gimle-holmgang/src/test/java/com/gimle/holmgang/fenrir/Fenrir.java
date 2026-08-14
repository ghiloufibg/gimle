package com.gimle.holmgang.fenrir;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.cluster.GimleCluster;
import com.gimle.holmgang.cluster.GimleProcess;
import com.gimle.holmgang.fenrir.ChaosLedger.Entry;
import com.gimle.holmgang.fenrir.ChaosLedger.Outcome;
import com.gimle.holmgang.heimdall.HolmgangConditionError;
import com.gimle.holmgang.loki.Loki;
import com.gimle.holmgang.topology.QuotaSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * The chaos executor: on a seeded schedule, it repeatedly strikes a healthy cluster from a {@link
 * FenrirPlan}'s weighted pools and, by default, gates every next strike on full recovery from the
 * last -- so a failure names one fault on a provably healthy cluster. {@link #unleash} blocks for
 * the soak window and returns the {@link ChaosLedger} of what happened.
 *
 * <p>Strictly one fault is in flight at a time; the schedule (inter-fault gaps and pool choices) is
 * drawn from the plan's seed in a fixed order, so a run replays from its seed. Victim selection
 * happens at fire time against live state, so replay reproduces the sequence and timing exactly and
 * the victims approximately -- as reproducible as a real-process harness can truthfully be.
 */
public final class Fenrir {

  /** How many replica indices to probe when enumerating a deployment's live workers. */
  private static final int MAX_REPLICA_PROBE = 8;

  private final GimleCluster cluster;
  private final FenrirPlan plan;
  private final ChaosSchedule schedule;
  private final Random victimRng;
  private final ChaosLedger ledger;
  private final long startNanos;

  private Fenrir(final GimleCluster cluster, final FenrirPlan plan) {
    this.cluster = cluster;
    this.plan = plan;
    this.schedule = new ChaosSchedule(plan.seed(), plan.gapMin(), plan.gapMax(), plan.pools());
    // A separate stream from the schedule's, seeded off the same seed: victim draws depend on live
    // state, so isolating them keeps the gap/pool sequence reproducible regardless.
    this.victimRng = new Random(plan.seed() * 31 + 1);
    this.ledger = new ChaosLedger(plan.seed());
    this.startNanos = System.nanoTime();
  }

  /** Runs {@code plan} against {@code cluster}, blocking for the soak window. */
  public static ChaosLedger unleash(final GimleCluster cluster, final FenrirPlan plan) {
    return new Fenrir(cluster, plan).run();
  }

  private ChaosLedger run() {
    final long soakNanos = plan.soak().toNanos();
    int index = 0;
    while (System.nanoTime() - startNanos < soakNanos) {
      // The inter-fault gap is an intrinsic part of the schedule, not a wait for an observation --
      // recovery itself is gated through Heimdall conditions, never a sleep.
      sleep(schedule.nextGapMillis());
      if (System.nanoTime() - startNanos >= soakNanos) {
        break;
      }
      index++;
      final Pool pool = schedule.nextPool();
      final StrikeResult result = strike(pool, index);
      ledger.record(result.entry());
      if (result.failure() != null && plan.convergeBetweenFaults()) {
        // Converge-then-strike: a missed recovery gate stops the soak immediately, so the ledger
        // names exactly one fault as the cause rather than burying it under later strikes.
        throw result.failure();
      }
    }
    return ledger;
  }

  private record StrikeResult(Entry entry, HolmgangConditionError failure) {}

  private StrikeResult strike(final Pool pool, final int index) {
    final long offset = elapsedMillis();
    return switch (pool.kind()) {
      case WORKER_KILL -> workerKill(pool, index, offset);
      case STORE_BOUNCE -> storeBounce(pool, index, offset);
      case LEADER_BOUNCE -> leaderBounce(pool, index, offset);
      case CONTROL_PLANE_BOUNCE -> controlPlaneBounce(pool, index, offset);
      case LINK_CUT -> linkCut(pool, index, offset);
      case FAFNIR_BOUNCE -> fafnirBounce(pool, index, offset);
    };
  }

  private StrikeResult workerKill(final Pool pool, final int index, final long offset) {
    final List<WorkerRef> workers = liveWorkers();
    if (workers.isEmpty()) {
      return skipped(index, pool, offset, "no live worker among eligible deployments");
    }
    final WorkerRef victim = workers.get(victimRng.nextInt(workers.size()));
    final long oldPid = victim.handle().pid();
    final String label = victim.deployment() + "#" + victim.index() + " (pid " + oldPid + ")";
    victim.handle().destroyForcibly();
    return gated(
        index,
        pool,
        offset,
        label,
        () -> {
          // Proven at the process level: the respawn outpaces any reliably-observable non-ACTIVE
          // control-plane view, so recovery is a new live worker for the same instance.
          probe(
                  "a new worker (not pid "
                      + oldPid
                      + ") hosts "
                      + victim.deployment()
                      + "#"
                      + victim.index(),
                  () ->
                      cluster
                          .workerFor(victim.deployment(), victim.index())
                          .map(handle -> handle.isAlive() && handle.pid() != oldPid)
                          .orElse(false))
              .await(plan.gateTimeout());
          cluster.when().deployment(victim.deployment()).isActive().await(plan.gateTimeout());
        });
  }

  private StrikeResult storeBounce(final Pool pool, final int index, final long offset) {
    final Optional<String> quorumSkip = quorumGuard();
    if (quorumSkip.isPresent()) {
      return skipped(index, pool, offset, quorumSkip.get());
    }
    final Optional<GimleProcess> leader = currentLeader();
    final List<GimleProcess> candidates = new ArrayList<>();
    for (int i = 0; i < cluster.storeCount(); i++) {
      final GimleProcess store = cluster.store(i);
      if (store.isAlive() && (leader.isEmpty() || !store.id().equals(leader.get().id()))) {
        candidates.add(store);
      }
    }
    if (candidates.isEmpty()) {
      return skipped(index, pool, offset, "no live non-leader store to bounce");
    }
    final GimleProcess victim = candidates.get(victimRng.nextInt(candidates.size()));
    final int members = cluster.storeMemberIds().size();
    bounce(victim, pool.dwell());
    return gated(index, pool, offset, victim.id(), () -> awaitStoreHealthy(members));
  }

  private StrikeResult leaderBounce(final Pool pool, final int index, final long offset) {
    final Optional<String> quorumSkip = quorumGuard();
    if (quorumSkip.isPresent()) {
      return skipped(index, pool, offset, quorumSkip.get());
    }
    final Optional<GimleProcess> leader = currentLeader();
    if (leader.isEmpty()) {
      return skipped(index, pool, offset, "no store leader currently known");
    }
    final int members = cluster.storeMemberIds().size();
    bounce(leader.get(), pool.dwell());
    return gated(
        index,
        pool,
        offset,
        leader.get().id() + " (leader)",
        () -> {
          probe("a store leader is elected again", () -> cluster.storeLeaderId().isPresent())
              .await(plan.gateTimeout());
          awaitStoreHealthy(members);
        });
  }

  private StrikeResult controlPlaneBounce(final Pool pool, final int index, final long offset) {
    final int total = cluster.controlPlaneCount();
    int serving = 0;
    for (int i = 0; i < total; i++) {
      if (cluster.controlPlane(i).isAlive() && cluster.api(i).isServing()) {
        serving++;
      }
    }
    // A single-CP topology is allowed to bounce -- the gate proves it comes back; a multi-CP one is
    // refused when only one replica still serves, so a bounce can never leave zero serving.
    if (total > 1 && serving <= 1) {
      return skipped(index, pool, offset, "control-plane floor: only one serving replica");
    }
    final List<Integer> aliveIndices = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      if (cluster.controlPlane(i).isAlive()) {
        aliveIndices.add(i);
      }
    }
    if (aliveIndices.isEmpty()) {
      return skipped(index, pool, offset, "no live control-plane replica to bounce");
    }
    final int victimIndex = aliveIndices.get(victimRng.nextInt(aliveIndices.size()));
    bounce(cluster.controlPlane(victimIndex), pool.dwell());
    return gated(
        index,
        pool,
        offset,
        cluster.controlPlane(victimIndex).id(),
        () ->
            probe(
                    "control-plane replica #" + victimIndex + " serves again",
                    () -> cluster.api(victimIndex).isServing())
                .await(plan.gateTimeout()));
  }

  private StrikeResult linkCut(final Pool pool, final int index, final long offset) {
    final Loki loki;
    try {
      loki = cluster.faults();
    } catch (final HolmgangException e) {
      return skipped(index, pool, offset, "topology is not fault-proxied");
    }
    final int total = cluster.controlPlaneCount();
    final int victimIndex = victimRng.nextInt(total);
    final Loki.Partition partition = loki.cutControlPlaneFromStores(victimIndex);
    try {
      probe(
              "control-plane replica #" + victimIndex + " stops serving while cut",
              () -> !cluster.api(victimIndex).isServing())
          .await(plan.gateTimeout());
    } catch (final HolmgangConditionError failure) {
      partition.heal();
      return new StrikeResult(
          failedEntry(index, pool, offset, "controlplane-" + victimIndex + " (link)"), failure);
    }
    // The cut is the fault; its dwell is how long the link stays severed before healing.
    sleep(pool.dwell().toMillis());
    partition.heal();
    return gated(
        index,
        pool,
        offset,
        "controlplane-" + victimIndex + " (link)",
        () ->
            probe(
                    "control-plane replica #" + victimIndex + " serves again after heal",
                    () -> cluster.api(victimIndex).isServing())
                .await(plan.gateTimeout()));
  }

  private StrikeResult fafnirBounce(final Pool pool, final int index, final long offset) {
    final int total = cluster.fafnirCount();
    final List<Integer> aliveIndices = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      if (cluster.fafnir(i).isAlive()) {
        aliveIndices.add(i);
      }
    }
    if (aliveIndices.isEmpty()) {
      return skipped(index, pool, offset, "no live Fafnir replica to bounce");
    }
    final int victimIndex = aliveIndices.get(victimRng.nextInt(aliveIndices.size()));
    bounce(cluster.fafnir(victimIndex), pool.dwell());
    return gated(
        index,
        pool,
        offset,
        cluster.fafnir(victimIndex).id(),
        () ->
            probe(
                    "a secret write round-trips again",
                    () -> {
                      try {
                        cluster.api().putSecret("holmgang-tenant", "fenrir-probe", "v");
                        return true;
                      } catch (final RuntimeException e) {
                        return false;
                      }
                    })
                .await(plan.gateTimeout()));
  }

  /** Runs a recovery gate, turning a missed deadline into a FAILED entry rather than a throw. */
  private StrikeResult gated(
      final int index,
      final Pool pool,
      final long offset,
      final String victim,
      final Runnable gate) {
    final long gateStart = System.nanoTime();
    try {
      gate.run();
    } catch (final HolmgangConditionError failure) {
      return new StrikeResult(failedEntry(index, pool, offset, victim), failure);
    }
    final long recoveryMillis = (System.nanoTime() - gateStart) / 1_000_000L;
    return new StrikeResult(
        new Entry(index, pool.kind(), victim, offset, Outcome.RECOVERED, recoveryMillis, null),
        null);
  }

  private StrikeResult skipped(
      final int index, final Pool pool, final long offset, final String reason) {
    return new StrikeResult(
        new Entry(index, pool.kind(), null, offset, Outcome.SKIPPED, -1L, reason), null);
  }

  private Entry failedEntry(
      final int index, final Pool pool, final long offset, final String victim) {
    return new Entry(index, pool.kind(), victim, offset, Outcome.FAILED, -1L, null);
  }

  /** Kill, hold dead for the dwell, then restart with the identical command line. */
  private void bounce(final GimleProcess process, final Duration dwell) {
    process.kill();
    sleep(dwell.toMillis());
    process.restart();
  }

  private void awaitStoreHealthy(final int expectedMembers) {
    probe(
            "the store reports " + expectedMembers + " members again",
            () -> cluster.storeMemberIds().size() == expectedMembers)
        .await(plan.gateTimeout());
    probe(
            "the cluster accepts writes again",
            () ->
                cluster.api().tryPutTenant("fenrir-write-probe", QuotaSpec.of(1024, 10, 1)) == 200)
        .await(plan.gateTimeout());
  }

  /** Skips a store/leader bounce when taking one member down would break quorum. */
  private Optional<String> quorumGuard() {
    final int total = cluster.storeCount();
    int live = 0;
    for (int i = 0; i < total; i++) {
      if (cluster.store(i).isAlive()) {
        live++;
      }
    }
    final int quorumFloor = total / 2 + 1;
    if (live <= quorumFloor) {
      return Optional.of("quorum floor: " + live + " of " + total + " members live");
    }
    return Optional.empty();
  }

  private Optional<GimleProcess> currentLeader() {
    try {
      return Optional.of(cluster.storeLeader());
    } catch (final HolmgangException e) {
      return Optional.empty();
    }
  }

  private com.gimle.holmgang.heimdall.HolmgangCondition probe(
      final String description, final java.util.function.BooleanSupplier condition) {
    return cluster.when().probe(description, condition);
  }

  /** A worker currently hosting one instance of an eligible deployment. */
  private record WorkerRef(String deployment, int index, ProcessHandle handle) {}

  private List<WorkerRef> liveWorkers() {
    final List<WorkerRef> workers = new ArrayList<>();
    for (final String deployment : plan.eligibleDeployments()) {
      for (int i = 0; i < MAX_REPLICA_PROBE; i++) {
        final int replicaIndex = i;
        cluster
            .workerFor(deployment, replicaIndex)
            .ifPresent(handle -> workers.add(new WorkerRef(deployment, replicaIndex, handle)));
      }
    }
    return workers;
  }

  private long elapsedMillis() {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  private void sleep(final long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HolmgangException("Fenrir soak was interrupted", e);
    }
  }
}
