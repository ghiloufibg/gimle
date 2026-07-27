package com.gimle.core.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GimleRaftExceptionTest {

  @Test
  void not_leader_names_the_node_and_known_leader() {
    GimleRaftException e = GimleRaftException.notLeader("node-1", Optional.of("node-2:9100"));
    assertTrue(e.getMessage().contains("node-1"));
    assertTrue(e.getMessage().contains("node-2:9100"));
  }

  @Test
  void not_leader_admits_when_no_leader_is_known() {
    GimleRaftException e = GimleRaftException.notLeader("node-1", Optional.empty());
    assertTrue(e.getMessage().contains("node-1"));
    assertTrue(e.getMessage().contains("leader unknown"));
  }

  @Test
  void proposal_timed_out_names_the_node_and_duration() {
    GimleRaftException e = GimleRaftException.proposalTimedOut("node-1", Duration.ofSeconds(5));
    assertTrue(e.getMessage().contains("node-1"));
    assertTrue(e.getMessage().contains("PT5S"));
  }

  @Test
  void snapshot_corrupted_names_the_file_and_carries_the_cause() {
    Path file = Path.of("raft", "snapshot", "state.bin");
    RuntimeException cause = new RuntimeException("truncated");
    GimleRaftException e = GimleRaftException.snapshotCorrupted(file, cause);
    assertTrue(e.getMessage().contains(file.toString()));
    assertEquals(cause, e.getCause());
  }
}
