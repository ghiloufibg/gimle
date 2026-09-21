package com.gimle.mimir.raft;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Regression probe for the Forseti-reported store leader-election fragility (self-recovering, seen
 * under out-of-order bring-up, ordinary baseline load, a control-plane-tier restart, and a
 * temporarily-desynced peer -- see {@code claudedocs} run-3 findings, not reproduced here from a
 * live cluster but from first principles against this harness).
 *
 * <p>{@link NornCheckQuorumJitterRegressionTest} already proves a *leader* does not self-demote
 * under ordinary RPC scheduling jitter, thanks to {@code RaftNode#CHECK_QUORUM_WINDOW} being
 * deliberately widened to twice the election timeout. That fix is leader-side only. A *follower*'s
 * own election timer -- reset only when an {@code AppendEntries} actually lands, at {@code
 * RaftNode#ELECTION_TIMEOUT_MIN_MS}/{@code MAX_MS} (150-300ms) -- has no equivalent widening. If
 * the same class of jitter that motivated doubling the leader's window also delays heartbeat
 * *delivery* to a follower past its own narrower window, the follower would spuriously become a
 * candidate and force an unnecessary (self-recovering) election -- the exact shape of the reported
 * fragility, and a gap this test exists to either confirm or rule out.
 */
@Isolated
class NornElectionTimeoutJitterRegressionTest {

  @TempDir Path tempDir;

  private static final long JITTER_MIN_MS = 50;
  private static final long JITTER_MAX_MS = 350;

  @Test
  @Timeout(value = 1, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_stable_leader_s_term_does_not_rise_under_realistic_follower_side_scheduling_jitter()
      throws Exception {
    try (NornCluster cluster =
        new NornCluster(
            tempDir.resolve("election-jitter"), List.of("node-1", "node-2", "node-3"))) {
      driveRealTime(cluster, Duration.ofSeconds(2));
      String leader = cluster.currentLeader().orElseThrow();
      long termBefore = cluster.termOf(leader);

      // Jitter every node, leader included -- the same shape NornCheckQuorumJitterRegressionTest
      // applies, since a real loaded host delays a process's outbound heartbeats exactly as much
      // as it delays a follower's own inbound processing of them.
      for (String id : cluster.nodeIds()) {
        cluster.setJitter(id, JITTER_MIN_MS, JITTER_MAX_MS);
      }
      driveRealTime(cluster, Duration.ofSeconds(4));

      long termAfter = cluster.termOf(leader);
      assertEquals(
          termBefore,
          termAfter,
          "the original leader's term rose under ordinary scheduling jitter ("
              + JITTER_MIN_MS
              + "-"
              + JITTER_MAX_MS
              + "ms) with no genuine fault injected -- a follower's own election timer fired"
              + " spuriously, forcing an unnecessary election");
    }
  }

  private static void driveRealTime(NornCluster cluster, Duration total)
      throws InterruptedException {
    Duration step = Duration.ofMillis(20);
    long steps = total.toMillis() / step.toMillis();
    for (long i = 0; i < steps; i++) {
      Thread.sleep(step.toMillis());
      cluster.advanceVirtualTime(step);
    }
  }
}
