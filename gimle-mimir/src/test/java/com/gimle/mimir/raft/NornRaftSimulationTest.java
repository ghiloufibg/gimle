package com.gimle.mimir.raft;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * The DST tier the flaky-test playbook's own item 11 named: a seeded, replayable fault schedule
 * (which node gets isolated/restarted, and when, in virtual time) driven over {@link NornCluster}'s
 * real in-process {@link RaftNode}s, checking Raft's own Election Safety and Log Matching
 * properties after every round and liveness once each seed's storm ends. Each seed reruns the
 * identical fault sequence (see {@link NornCluster}'s own javadoc for exactly what is and isn't
 * bit-for-bit deterministic underneath that sequence) -- a failing seed printed below reproduces by
 * rerunning just {@link #runSeed} with that one {@code long}, not the whole sweep.
 *
 * <p>Far more election/partition/recovery activity per real second than a live-timer test could
 * ever afford: {@link NornCluster#advanceVirtualTime} fires every due election-timeout/check-quorum
 * tick for free, so a round that would cost a real test 150-300ms of wall-clock waiting costs this
 * one only the real time its background threads need to actually process the resulting RPCs (tens
 * of milliseconds, not hundreds).
 */
// Same real-background-thread timing sensitivity RaftClusterTest documents on its own @Isolated --
// this harness's virtual-time compression doesn't remove that, only shrinks how much of it there
// is.
@Isolated
class NornRaftSimulationTest {

  @TempDir Path tempDir;

  private static final List<String> NODE_IDS = List.of("node-1", "node-2", "node-3");
  private static final int SEEDS = 20;
  private static final int ROUNDS_PER_SEED = 40;

  @Test
  @Timeout(value = 5, unit = java.util.concurrent.TimeUnit.MINUTES)
  void raft_safety_invariants_hold_across_many_seeded_fault_schedules() throws Exception {
    for (long seed = 0; seed < SEEDS; seed++) {
      runSeed(seed, tempDir.resolve("seed-" + seed));
    }
  }

  private void runSeed(long seed, Path root) throws Exception {
    Random random = new Random(seed);
    List<String> ledger = new ArrayList<>();
    try (NornCluster cluster = new NornCluster(root, NODE_IDS)) {
      for (int round = 1; round <= ROUNDS_PER_SEED; round++) {
        applyRandomAction(cluster, random, round, ledger);

        Duration step = Duration.ofMillis(50 + random.nextInt(350));
        cluster.advanceVirtualTime(step);
        cluster.settleReal();

        try {
          cluster.assertSafetyInvariants("seed " + seed + " round " + round);
        } catch (AssertionError e) {
          throw new AssertionError(
              "seed "
                  + seed
                  + " failed at round "
                  + round
                  + " -- reproduce with runSeed("
                  + seed
                  + ", ...); ledger:\n"
                  + String.join("\n", ledger),
              e);
        }
      }

      cluster.healAll();
      ledger.add("healAll (post-storm recovery window)");
      try {
        cluster.assertEventuallyLive(Duration.ofSeconds(10));
      } catch (AssertionError e) {
        throw new AssertionError(
            "seed "
                + seed
                + " never recovered liveness after its storm -- reproduce with runSeed("
                + seed
                + ", ...); ledger:\n"
                + String.join("\n", ledger),
            e);
      }
    }
  }

  /**
   * One weighted, seeded decision per round -- proposals and healing outweigh isolate/restart so a
   * cluster spends most of its simulated life recovering and making progress, not permanently
   * wedged, the same "converge, then strike" balance {@code Fenrir}'s own real-cluster chaos soak
   * strikes for the identical reason (see its own package javadoc).
   */
  private static void applyRandomAction(
      NornCluster cluster, Random random, int round, List<String> ledger) {
    int roll = random.nextInt(100);
    if (roll < 15) {
      String target = NODE_IDS.get(random.nextInt(NODE_IDS.size()));
      cluster.isolate(target);
      ledger.add(round + ": isolate " + target);
    } else if (roll < 25) {
      String target = NODE_IDS.get(random.nextInt(NODE_IDS.size()));
      cluster.restart(target);
      ledger.add(round + ": restart " + target);
    } else if (roll < 45) {
      cluster.healAll();
      ledger.add(round + ": healAll");
    } else if (roll < 80) {
      cluster
          .currentLeader()
          .ifPresentOrElse(
              leader -> {
                boolean applied =
                    cluster.tryPropose(
                        leader,
                        new StateMutation.PutTenant(
                            new com.gimle.core.tenant.Tenant(
                                "norn-t-" + round,
                                new com.gimle.core.tenant.ResourceQuota(1, 1, 1))));
                ledger.add(round + ": propose via " + leader + " -> " + applied);
              },
              () -> ledger.add(round + ": propose skipped -- no known leader"));
    } else {
      ledger.add(round + ": no-op (time advance only)");
    }
  }
}
