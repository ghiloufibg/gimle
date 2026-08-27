package com.gimle.ragnarok.fenrir;

import com.gimle.ragnarok.RagnarokException;
import com.gimle.ragnarok.fenrir.ChaosLedger.Entry;
import com.gimle.ragnarok.fenrir.ChaosLedger.Outcome;
import com.gimle.ragnarok.target.ClusterTarget;
import com.gimle.ragnarok.target.GimleProcess;
import com.gimle.ragnarok.target.NetworkFaultInjector;
import com.gimle.ragnarok.target.WorkerHandle;
import com.gimle.testkit.heimdall.HeimdallCondition;
import com.gimle.testkit.heimdall.HeimdallConditionError;
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
 *
 * <p>A {@link ClusterTarget} with no process control or network-fault interposition of its own (an
 * HTTP-only target) can never make a candidate list for the affected fault kinds non-empty -- every
 * strike of that kind is recorded {@link Outcome#SKIPPED} with a reason, never thrown.
 */
public final class Fenrir {

  /** How many replica indices to probe when enumerating a deployment's live workers. */
  private static final int MAX_REPLICA_PROBE = 8;

  private final ClusterTarget cluster;
  private final FenrirPlan plan;
  private final ChaosSchedule schedule;
  private final Random victimRng;
  private final ChaosLedger ledger;
  private final long startNanos;

  private Fenrir(final ClusterTarget cluster, final FenrirPlan plan) {
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
  public static ChaosLedger unleash(final ClusterTarget cluster, final FenrirPlan plan) {
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

  private record StrikeResult(Entry entry, HeimdallConditionError failure) {}

  private StrikeResult strike(final Pool pool, final int index) {
    final long offset = elapsedMillis();
    return switch (pool.kind()) {
      case WORKER_KILL -> workerKill(pool, index, offset);
      case STORE_BOUNCE -> storeBounce(pool, index, offset);
      case LEADER_BOUNCE -> leaderBounce(pool, index, offset);
      case CONTROL_PLANE_BOUNCE -> controlPlaneBounce(pool, index, offset);
      case LINK_CUT -> linkCut(pool, index, offset);
      case STORE_PARTITION -> storePartition(pool, index, offset);
      case FAFNIR_BOUNCE -> fafnirBounce(pool, index, offset);
      case MUNINN_BOUNCE -> muninnBounce(pool, index, offset);
      case ANDVARI_BOUNCE -> andvariBounce(pool, index, offset);
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
    victim.handle().kill();
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
    final Optional<GimleProcess> leader = cluster.storeLeader();
    final List<GimleProcess> candidates = new ArrayList<>();
    for (int i = 0; i < cluster.storeCount(); i++) {
      final int storeIndex = i;
      cluster
          .store(storeIndex)
          .filter(GimleProcess::isAlive)
          .filter(store -> leader.isEmpty() || !store.id().equals(leader.get().id()))
          .ifPresent(candidates::add);
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
    final Optional<GimleProcess> leader = cluster.storeLeader();
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
      if (isAlive(cluster.controlPlane(i)) && cluster.api(i).isServing()) {
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
      if (isAlive(cluster.controlPlane(i))) {
        aliveIndices.add(i);
      }
    }
    if (aliveIndices.isEmpty()) {
      return skipped(index, pool, offset, "no live control-plane replica to bounce");
    }
    final int victimIndex = aliveIndices.get(victimRng.nextInt(aliveIndices.size()));
    final GimleProcess victim = cluster.controlPlane(victimIndex).orElseThrow();
    bounce(victim, pool.dwell());
    return gated(
        index,
        pool,
        offset,
        victim.id(),
        () ->
            probe(
                    "control-plane replica #" + victimIndex + " serves again",
                    () -> cluster.api(victimIndex).isServing())
                .await(plan.gateTimeout()));
  }

  private StrikeResult linkCut(final Pool pool, final int index, final long offset) {
    final Optional<NetworkFaultInjector> faults = cluster.faults();
    if (faults.isEmpty()) {
      return skipped(index, pool, offset, "topology is not fault-proxied");
    }
    final int total = cluster.controlPlaneCount();
    final int victimIndex = victimRng.nextInt(total);
    final NetworkFaultInjector.Partition partition =
        faults.get().cutControlPlaneFromStores(victimIndex);
    try {
      probe(
              "control-plane replica #" + victimIndex + " stops serving while cut",
              () -> !cluster.api(victimIndex).isServing())
          .await(plan.gateTimeout());
    } catch (final HeimdallConditionError failure) {
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

  private StrikeResult storePartition(final Pool pool, final int index, final long offset) {
    final Optional<NetworkFaultInjector> faults = cluster.faults();
    if (faults.isEmpty()) {
      return skipped(index, pool, offset, "topology is not fault-proxied");
    }
    final Optional<String> quorumSkip = quorumGuard();
    if (quorumSkip.isPresent()) {
      return skipped(index, pool, offset, quorumSkip.get());
    }
    final List<Integer> candidates = new ArrayList<>();
    for (int i = 0; i < cluster.storeCount(); i++) {
      if (isAlive(cluster.store(i))) {
        candidates.add(i);
      }
    }
    if (candidates.isEmpty()) {
      return skipped(index, pool, offset, "no live store to partition");
    }
    final int victimIndex = candidates.get(victimRng.nextInt(candidates.size()));
    final int members = cluster.storeMemberIds().size();
    final NetworkFaultInjector.Partition partition = faults.get().cutStoreFromPeers(victimIndex);
    sleep(pool.dwell().toMillis());
    partition.heal();
    return gated(
        index,
        pool,
        offset,
        "store-" + victimIndex + " (partition)",
        () -> awaitStoreHealthy(members));
  }

  private StrikeResult fafnirBounce(final Pool pool, final int index, final long offset) {
    final int total = cluster.fafnirCount();
    final List<Integer> aliveIndices = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      if (isAlive(cluster.fafnir(i))) {
        aliveIndices.add(i);
      }
    }
    if (aliveIndices.isEmpty()) {
      return skipped(index, pool, offset, "no live Fafnir replica to bounce");
    }
    final int victimIndex = aliveIndices.get(victimRng.nextInt(aliveIndices.size()));
    final GimleProcess victim = cluster.fafnir(victimIndex).orElseThrow();
    bounce(victim, pool.dwell());
    return gated(
        index,
        pool,
        offset,
        victim.id(),
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

  private StrikeResult muninnBounce(final Pool pool, final int index, final long offset) {
    final int total = cluster.muninnCount();
    final List<Integer> aliveIndices = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      if (isAlive(cluster.muninn(i))) {
        aliveIndices.add(i);
      }
    }
    final Optional<String> floorSkip = replicaFloorGuard("muninn", aliveIndices.size(), total);
    if (floorSkip.isPresent()) {
      return skipped(index, pool, offset, floorSkip.get());
    }
    final int victimIndex = aliveIndices.get(victimRng.nextInt(aliveIndices.size()));
    final GimleProcess victim = cluster.muninn(victimIndex).orElseThrow();
    bounce(victim, pool.dwell());
    return gated(
        index,
        pool,
        offset,
        victim.id(),
        () ->
            probe(
                    "muninn replica #" + victimIndex + " serves again",
                    () -> cluster.muninnServing(victimIndex))
                .await(plan.gateTimeout()));
  }

  private StrikeResult andvariBounce(final Pool pool, final int index, final long offset) {
    final int total = cluster.andvariCount();
    final List<Integer> aliveIndices = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      if (isAlive(cluster.andvari(i))) {
        aliveIndices.add(i);
      }
    }
    final Optional<String> floorSkip = replicaFloorGuard("andvari", aliveIndices.size(), total);
    if (floorSkip.isPresent()) {
      return skipped(index, pool, offset, floorSkip.get());
    }
    final int victimIndex = aliveIndices.get(victimRng.nextInt(aliveIndices.size()));
    final GimleProcess victim = cluster.andvari(victimIndex).orElseThrow();
    bounce(victim, pool.dwell());
    return gated(
        index,
        pool,
        offset,
        victim.id(),
        () ->
            probe(
                    "andvari replica #" + victimIndex + " serves again",
                    () -> cluster.andvariServing(victimIndex))
                .await(plan.gateTimeout()));
  }

  /** True when a process accessor resolved to a live process; false when absent or dead. */
  private static boolean isAlive(final Optional<GimleProcess> process) {
    return process.map(GimleProcess::isAlive).orElse(false);
  }

  /**
   * Skips a Muninn/Andvari bounce when at most one replica of that process is currently alive --
   * bouncing the last one would just be an ordinary "kill the one instance" test, not the
   * failover/fan-out property multi-replica topologies exist to exercise. Unlike {@link
   * #quorumGuard}, this is a plain "more than one left" floor, not a majority computation: neither
   * process runs Raft, so there is no quorum to protect, only a peer to fail over to.
   */
  private Optional<String> replicaFloorGuard(
      final String processLabel, final int aliveCount, final int totalCount) {
    if (aliveCount <= 1) {
      return Optional.of(
          processLabel + " floor: only " + aliveCount + " of " + totalCount + " replicas live");
    }
    return Optional.empty();
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
    } catch (final HeimdallConditionError failure) {
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
            () -> cluster.api().tryPutTenant("fenrir-write-probe", 1024, 10, 1) == 200)
        .await(plan.gateTimeout());
  }

  /** Skips a store/leader bounce when taking one member down would break quorum. */
  private Optional<String> quorumGuard() {
    final int total = cluster.storeCount();
    int live = 0;
    for (int i = 0; i < total; i++) {
      if (isAlive(cluster.store(i))) {
        live++;
      }
    }
    final int quorumFloor = total / 2 + 1;
    if (live <= quorumFloor) {
      return Optional.of("quorum floor: " + live + " of " + total + " members live");
    }
    return Optional.empty();
  }

  private HeimdallCondition probe(
      final String description, final java.util.function.BooleanSupplier condition) {
    return cluster.when().probe(description, condition);
  }

  /** A worker currently hosting one instance of an eligible deployment. */
  private record WorkerRef(String deployment, int index, WorkerHandle handle) {}

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
      throw new RagnarokException("Fenrir soak was interrupted", e);
    }
  }
}
