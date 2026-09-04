package com.gimle.mimir.raft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Regression for M39: a stable leader must not self-demote (see {@code RaftNode#checkQuorumTick}'s
 * own "check-quorum self-demotion" log line) under ordinary real-world RPC scheduling delay -- CPU
 * contention on the sender's or receiver's own host, not a genuine network partition -- while a
 * *genuine* total partition must still be detected reasonably quickly. {@link
 * NornCluster#setJitter} simulates that contention directly: every RPC to or from the jittered node
 * sleeps a real, random duration before proceeding, exactly the shape of delay a loaded host's
 * scheduler introduces, as opposed to {@link NornCluster#isolate}'s unconditional failure.
 *
 * <p>Measured directly rather than asserted from theory: at the old {@code CHECK_QUORUM_WINDOW}
 * (one election timeout), a leader self-demoted on 9 of 10 trials under this same jitter range;
 * doubling the window (see {@code RaftNode#CHECK_QUORUM_WINDOW}'s own javadoc) eliminated every
 * spurious demotion across repeated runs while a genuine partition was still caught in a few
 * hundred milliseconds either way.
 */
@Isolated
class NornCheckQuorumJitterRegressionTest {

  @TempDir Path tempDir;

  // Real background-thread scheduling noise in a shared, CPU-contended sandbox can occasionally
  // exceed even this range; a genuine failure here should be re-run in isolation before treating
  // it as a real regression, per this repo's own accepted-flakiness posture for real-socket/timing
  // tests -- but it should not fail routinely, which is exactly what this test proves against the
  // fix.
  private static final long JITTER_MIN_MS = 50;
  private static final long JITTER_MAX_MS = 350;

  @Test
  @Timeout(value = 1, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_stable_leader_does_not_self_demote_under_realistic_scheduling_jitter() throws Exception {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RaftNode.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try (NornCluster cluster =
        new NornCluster(tempDir.resolve("jitter"), List.of("node-1", "node-2", "node-3"))) {
      driveRealTime(cluster, Duration.ofSeconds(2));
      appender.list.clear();

      for (String id : cluster.nodeIds()) {
        cluster.setJitter(id, JITTER_MIN_MS, JITTER_MAX_MS);
      }
      driveRealTime(cluster, Duration.ofSeconds(4));

      assertEquals(
          0,
          countDemotions(appender),
          "a stable leader self-demoted under ordinary scheduling jitter ("
              + JITTER_MIN_MS
              + "-"
              + JITTER_MAX_MS
              + "ms), not a genuine partition");
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  @Timeout(value = 1, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_genuine_total_partition_is_still_detected_promptly() throws Exception {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RaftNode.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try (NornCluster cluster =
        new NornCluster(tempDir.resolve("partition"), List.of("node-1", "node-2", "node-3"))) {
      driveRealTime(cluster, Duration.ofSeconds(2));
      String leader = cluster.currentLeader().orElseThrow();
      // A genuine, total minority partition: the leader cut off from both followers, no jitter.
      cluster.isolate(leader);
      Instant isolatedAt = Instant.now();
      Instant demotedAt = null;
      for (int i = 0; i < 100 && demotedAt == null; i++) {
        Thread.sleep(20);
        cluster.advanceVirtualTime(Duration.ofMillis(20));
        if (countDemotions(appender) > 0) {
          demotedAt = Instant.now();
        }
      }
      assertTrue(demotedAt != null, "leader never self-demoted after a genuine total partition");
      // Generous relative to the doubled check-quorum window (see RaftNode#CHECK_QUORUM_WINDOW):
      // proves detection is still prompt, not that it hits any particular bound exactly.
      assertTrue(
          Duration.between(isolatedAt, demotedAt).compareTo(Duration.ofSeconds(3)) < 0,
          "genuine partition took too long to be detected: "
              + Duration.between(isolatedAt, demotedAt));
    } finally {
      logger.detachAppender(appender);
    }
  }

  private static long countDemotions(ListAppender<ILoggingEvent> appender) {
    return appender.list.stream()
        .filter(e -> e.getFormattedMessage().contains("check-quorum self-demotion"))
        .count();
  }

  /**
   * Advances the cluster's virtual clock in small steps, each preceded by a matching real sleep, so
   * real elapsed time and the cluster's own virtual clock stay in lockstep -- letting a real delay
   * (background-thread scheduling noise, or a {@link NornCluster#setJitter} injection) on a peer
   * RPC actually register as staleness against {@code clock.instant()} by the time it completes,
   * rather than the clock sitting frozen for the whole real delay the way one big jump-then-settle
   * round would leave it.
   */
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
