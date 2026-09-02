package com.gimle.mimir.raft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.mimir.store.StateSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RaftLogTest {

  @TempDir Path tempDir;

  private static LogEntry entry(long term, long index) {
    return new LogEntry(
        term,
        index,
        new StateMutation.RemoveDeployment(Optional.empty(), "deployment-" + index, 0));
  }

  /** Every field empty/default -- a base other tests build on by overriding just what they need. */
  private static StateSnapshot emptySnapshot() {
    return new StateSnapshot(
        List.of(), // deployments
        Map.of(), // deploymentGenerations
        List.of(), // assignments
        List.of(), // jobSpecs
        List.of(), // jobRuns
        Map.of(), // jobPhases
        List.of(), // jobRunSummaries
        List.of(), // cronJobSpecs
        Map.of(), // cronJobLastSchedule
        List.of(), // daemonSetSpecs
        List.of(), // daemonSetAssignments
        Map.of(), // rollingDaemonSetNodes
        List.of(), // statefulSetSpecs
        List.of(), // statefulSetAssignments
        Map.of(), // rollingStatefulSetIndices
        Map.of(), // statefulSetIndexNodes
        List.of(), // nodeRegistrations
        Map.of(), // rollingIndices
        Map.of(), // surgeIndices
        Map.of(), // effectiveReplicas
        List.of(), // tenants
        Set.of(), // quotaViolatingDeployments
        List.of(), // configEntries
        List.of(), // roles
        List.of(), // roleBindings
        List.of(), // accounts
        List.of(), // reconcilerInstanceStates
        Set.of(), // cordonedNodes
        Map.of(), // instanceEvents
        List.of(), // auditEvents
        List.of(), // services
        List.of(), // networkPolicies
        List.of(), // controllerRevisions
        List.of(), // limitRanges
        Map.of(), // limitRangeViolations
        Set.of(), // revokedCertificateSerials
        List.of(), // workloadTokens
        Map.of(), // nodeTaints
        List.of(), // kindDefinitions
        List.of(), // customResources
        List.of(), // workloadHealthStates
        Map.of(), // sessionRevokedBeforeEpochMilli
        List.of(), // alertRules
        Map.of(), List.of()); // deploymentLastScale
  }

  /** {@link #emptySnapshot()} with only {@code quotaViolatingDeployments} overridden. */
  private static StateSnapshot snapshotWithQuotaViolatingDeployments(
      Set<String> quotaViolatingDeployments) {
    StateSnapshot base = emptySnapshot();
    return new StateSnapshot(
        base.deployments(),
        base.deploymentGenerations(),
        base.assignments(),
        base.jobSpecs(),
        base.jobRuns(),
        base.jobPhases(),
        base.jobRunSummaries(),
        base.cronJobSpecs(),
        base.cronJobLastSchedule(),
        base.daemonSetSpecs(),
        base.daemonSetAssignments(),
        base.rollingDaemonSetNodes(),
        base.statefulSetSpecs(),
        base.statefulSetAssignments(),
        base.rollingStatefulSetIndices(),
        base.statefulSetIndexNodes(),
        base.nodeRegistrations(),
        base.rollingIndices(),
        base.surgeIndices(),
        base.effectiveReplicas(),
        base.tenants(),
        quotaViolatingDeployments,
        base.configEntries(),
        base.roles(),
        base.roleBindings(),
        base.accounts(),
        base.reconcilerInstanceStates(),
        base.cordonedNodes(),
        base.instanceEvents(),
        base.auditEvents(),
        base.services(),
        base.networkPolicies(),
        base.controllerRevisions(),
        base.limitRanges(),
        base.limitRangeViolations(),
        base.revokedCertificateSerials(),
        base.workloadTokens(),
        base.nodeTaints(),
        base.kindDefinitions(),
        base.customResources(),
        base.workloadHealthStates(),
        base.sessionRevokedBeforeEpochMilli(),
        base.alertRules(),
        base.deploymentLastScale(),
        List.of());
  }

  @Test
  void appends_and_reads_back_entries() {
    RaftLog log = new RaftLog(tempDir.resolve("log1"));
    log.append(entry(1, 1));
    log.append(entry(1, 2));
    log.append(entry(2, 3));

    assertEquals(Optional.of(entry(1, 1)), log.get(1));
    assertEquals(3, log.entriesFrom(1).size());
    assertEquals(2, log.entriesFrom(2).size());
    assertEquals(3L, log.lastIndex());
    assertEquals(2L, log.lastTerm());
    assertEquals(1L, log.termAt(1));
    assertEquals(2L, log.termAt(3));
  }

  @Test
  void get_of_a_missing_index_is_empty() {
    RaftLog log = new RaftLog(tempDir.resolve("log2"));
    assertEquals(Optional.empty(), log.get(5));
  }

  @Test
  void a_fresh_log_reports_index_and_term_zero() {
    RaftLog log = new RaftLog(tempDir.resolve("log3"));
    assertEquals(0L, log.lastIndex());
    assertEquals(0L, log.lastTerm());
    assertEquals(0L, log.termAt(0));
  }

  @Test
  void truncate_from_deletes_the_conflicting_suffix() {
    RaftLog log = new RaftLog(tempDir.resolve("log4"));
    log.append(entry(1, 1));
    log.append(entry(1, 2));
    log.append(entry(1, 3));

    log.truncateFrom(2);

    assertEquals(1L, log.lastIndex());
    assertEquals(Optional.empty(), log.get(2));
    assertEquals(Optional.empty(), log.get(3));
  }

  @Test
  void term_and_vote_persist_across_reopen() {
    Path dir = tempDir.resolve("log5");
    RaftLog log = new RaftLog(dir);
    log.setTermAndVote(4, Optional.of("node-2"));

    RaftLog reopened = new RaftLog(dir);
    assertEquals(4L, reopened.currentTerm());
    assertEquals(Optional.of("node-2"), reopened.votedFor());
  }

  @Test
  void an_empty_vote_persists_as_empty_across_reopen() {
    Path dir = tempDir.resolve("log6");
    RaftLog log = new RaftLog(dir);
    log.setTermAndVote(2, Optional.empty());

    RaftLog reopened = new RaftLog(dir);
    assertEquals(2L, reopened.currentTerm());
    assertEquals(Optional.empty(), reopened.votedFor());
  }

  @Test
  void reopening_recovers_every_persisted_entry() {
    Path dir = tempDir.resolve("log7");
    RaftLog log = new RaftLog(dir);
    log.append(entry(1, 1));
    log.append(entry(1, 2));
    log.append(entry(2, 3));

    RaftLog reopened = new RaftLog(dir);
    assertEquals(3L, reopened.lastIndex());
    assertEquals(List.of(entry(1, 1), entry(1, 2), entry(2, 3)), reopened.entriesFrom(1));
  }

  @Test
  void install_snapshot_persists_and_discards_compacted_entries() {
    RaftLog log = new RaftLog(tempDir.resolve("log8"));
    log.append(entry(1, 1));
    log.append(entry(1, 2));
    log.append(entry(2, 3));

    StateSnapshot snapshot = emptySnapshot();
    log.installSnapshot(2, 1, RaftCodec.encodeSnapshot(snapshot));

    assertEquals(2L, log.snapshotLastIncludedIndex());
    assertEquals(1L, log.snapshotLastIncludedTerm());
    assertEquals(Optional.empty(), log.get(1));
    assertEquals(Optional.empty(), log.get(2));
    assertEquals(Optional.of(entry(2, 3)), log.get(3));
    assertEquals(1L, log.termAt(2));
    assertEquals(3L, log.lastIndex());
  }

  @Test
  void a_far_behind_node_recovers_the_snapshot_floor_and_bytes_across_reopen() {
    Path dir = tempDir.resolve("log9");
    RaftLog log = new RaftLog(dir);
    StateSnapshot snapshot = snapshotWithQuotaViolatingDeployments(Set.of("orders"));
    log.installSnapshot(10, 3, RaftCodec.encodeSnapshot(snapshot));

    RaftLog reopened = new RaftLog(dir);
    assertEquals(10L, reopened.snapshotLastIncludedIndex());
    assertEquals(3L, reopened.snapshotLastIncludedTerm());
    assertEquals(10L, reopened.lastIndex());
    assertEquals(3L, reopened.termAt(10));
    assertTrue(reopened.loadSnapshot().isPresent());
    assertEquals(Set.of("orders"), reopened.loadSnapshot().get().quotaViolatingDeployments());
  }

  @Test
  void a_log_with_no_snapshot_has_no_loadable_snapshot() {
    RaftLog log = new RaftLog(tempDir.resolve("log10"));
    assertFalse(log.loadSnapshot().isPresent());
  }

  @Test
  void a_corrupted_wal_record_with_intact_records_after_it_fails_loudly_at_construction()
      throws Exception {
    Path dir = tempDir.resolve("log11");
    RaftLog log = new RaftLog(dir);
    log.append(entry(1, 1));
    log.append(entry(1, 2));
    log.close();

    Path segment = soleWalSegment(dir);
    byte[] bytes = Files.readAllBytes(segment);
    // Flip one bit inside the first record's payload: its checksum now fails while the second
    // record stays intact -- damage no crash-during-append can produce, so replay must refuse
    // rather than silently discard the acknowledged second entry.
    bytes[10] ^= 0x01;
    Files.write(segment, bytes);

    assertThrows(IllegalStateException.class, () -> new RaftLog(dir));
  }

  @Test
  void a_torn_tail_from_a_crash_mid_append_is_discarded_and_the_log_stays_usable()
      throws Exception {
    Path dir = tempDir.resolve("log13");
    RaftLog log = new RaftLog(dir);
    log.append(entry(1, 1));
    log.append(entry(1, 2));
    log.close();

    Path segment = soleWalSegment(dir);
    byte[] bytes = Files.readAllBytes(segment);
    // Chop the last few bytes off the final record, as a crash mid-append would.
    Files.write(segment, Arrays.copyOf(bytes, bytes.length - 3));

    RaftLog reopened = new RaftLog(dir);
    assertEquals(1L, reopened.lastIndex());
    assertEquals(Optional.empty(), reopened.get(2));
    reopened.append(entry(2, 2));
    assertEquals(Optional.of(entry(2, 2)), reopened.get(2));
  }

  @Test
  void a_truncation_with_nothing_reappended_over_it_survives_reopen() {
    Path dir = tempDir.resolve("log14");
    RaftLog log = new RaftLog(dir);
    log.append(entry(1, 1));
    log.append(entry(1, 2));
    log.append(entry(1, 3));
    log.truncateFrom(2);
    log.close();

    RaftLog reopened = new RaftLog(dir);
    assertEquals(1L, reopened.lastIndex());
    assertEquals(Optional.empty(), reopened.get(2));
    assertEquals(Optional.empty(), reopened.get(3));
  }

  @Test
  void an_entry_reappended_after_truncation_supersedes_the_old_suffix_on_reopen() {
    Path dir = tempDir.resolve("log15");
    RaftLog log = new RaftLog(dir);
    log.append(entry(1, 1));
    log.append(entry(1, 2));
    log.append(entry(1, 3));
    log.truncateFrom(2);
    log.append(entry(2, 2));
    log.close();

    RaftLog reopened = new RaftLog(dir);
    assertEquals(2L, reopened.lastIndex());
    assertEquals(2L, reopened.termAt(2));
    assertEquals(Optional.empty(), reopened.get(3));
  }

  private static Path soleWalSegment(Path dir) throws Exception {
    try (var files = Files.list(dir.resolve("wal"))) {
      return files
          .filter(f -> f.getFileName().toString().endsWith(".wal"))
          .findFirst()
          .orElseThrow();
    }
  }

  @Test
  void term_at_an_index_that_was_never_written_and_is_not_the_snapshot_floor_throws() {
    RaftLog log = new RaftLog(tempDir.resolve("log12"));
    assertThrows(IllegalArgumentException.class, () -> log.termAt(5));
  }
}
