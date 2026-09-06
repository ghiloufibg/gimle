package com.gimle.hilmir.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunLedgerTest {

  @TempDir Path tempDir;

  private static final RunRecord STORE =
      new RunRecord(
          "store-0",
          "STORE",
          12345L,
          List.of("java", "-cp", "cp", "com.gimle.mimir.StoreMain"),
          "store-0.log",
          "127.0.0.1:9091");
  private static final RunRecord AGENT =
      new RunRecord(
          "agent-a",
          "AGENT",
          67890L,
          List.of("java", "-cp", "cp", "com.gimle.agent.AgentMain"),
          "agent-a.log",
          "");

  @Test
  void round_trips_every_field_of_every_record_through_json() {
    RunLedger.write(tempDir, List.of(STORE, AGENT));

    final List<RunRecord> read = RunLedger.read(tempDir);

    assertEquals(List.of(STORE, AGENT), read);
  }

  @Test
  void writing_creates_the_data_root_if_it_does_not_yet_exist() {
    final Path nested = tempDir.resolve("nested/data/root");
    RunLedger.write(nested, List.of(STORE));

    assertTrue(Files.isDirectory(nested));
    assertEquals(List.of(STORE), RunLedger.read(nested));
  }

  @Test
  void an_empty_record_list_round_trips_to_an_empty_list() {
    RunLedger.write(tempDir, List.of());

    assertEquals(List.of(), RunLedger.read(tempDir));
  }

  @Test
  void reading_with_no_ledger_present_reports_that_no_run_was_recorded() {
    final HilmirException e = assertThrows(HilmirException.class, () -> RunLedger.read(tempDir));

    assertTrue(e.getMessage().contains("no run recorded"));
  }

  @Test
  void reading_a_corrupt_ledger_file_reports_it_as_corrupt_rather_than_propagating_a_raw_error()
      throws Exception {
    Files.writeString(tempDir.resolve("hilmir-run.json"), "not json at all {{{");

    final HilmirException e = assertThrows(HilmirException.class, () -> RunLedger.read(tempDir));

    assertTrue(e.getMessage().contains("corrupt"));
  }

  @Test
  void removing_one_entry_leaves_every_other_record_untouched() {
    RunLedger.write(tempDir, List.of(STORE, AGENT));

    RunLedger.remove(tempDir, "store-0");

    assertEquals(List.of(AGENT), RunLedger.read(tempDir));
  }

  @Test
  void removing_the_last_entry_leaves_an_empty_ledger_rather_than_no_ledger_at_all() {
    RunLedger.write(tempDir, List.of(STORE));

    RunLedger.remove(tempDir, "store-0");

    assertEquals(List.of(), RunLedger.read(tempDir));
  }

  @Test
  void removing_an_unknown_id_reports_it_rather_than_silently_rewriting_the_ledger() {
    RunLedger.write(tempDir, List.of(STORE));

    final HilmirException e =
        assertThrows(HilmirException.class, () -> RunLedger.remove(tempDir, "store-9"));

    assertTrue(e.getMessage().contains("store-9"));
    assertEquals(List.of(STORE), RunLedger.read(tempDir));
  }

  @Test
  void deleting_an_absent_ledger_is_a_clean_no_op() {
    RunLedger.delete(tempDir);
  }

  @Test
  void deleting_removes_the_ledger_file() {
    RunLedger.write(tempDir, List.of(STORE));
    RunLedger.delete(tempDir);

    assertThrows(HilmirException.class, () -> RunLedger.read(tempDir));
  }

  @Test
  void replacing_one_entry_leaves_every_other_entry_byte_for_byte_unchanged() {
    final RunRecord controlPlane =
        new RunRecord(
            "controlplane-0",
            "CONTROL_PLANE",
            11111L,
            List.of("java", "-cp", "cp", "com.gimle.controlplane.ControlPlaneMain"),
            "controlplane-0.log",
            "127.0.0.1:8080");
    RunLedger.write(tempDir, List.of(STORE, AGENT, controlPlane));

    final RunRecord restartedStore =
        new RunRecord(
            "store-0",
            "STORE",
            99999L,
            List.of("java", "-cp", "cp2", "com.gimle.mimir.StoreMain"),
            "store-0.log",
            "127.0.0.1:9091");
    RunLedger.replace(tempDir, "store-0", restartedStore);

    assertEquals(List.of(restartedStore, AGENT, controlPlane), RunLedger.read(tempDir));
  }

  @Test
  void replacing_a_nonexistent_id_fails_clearly_and_leaves_the_ledger_untouched() {
    RunLedger.write(tempDir, List.of(STORE, AGENT));

    final HilmirException e =
        assertThrows(HilmirException.class, () -> RunLedger.replace(tempDir, "store-99", STORE));

    assertTrue(e.getMessage().contains("store-99"));
    assertEquals(List.of(STORE, AGENT), RunLedger.read(tempDir));
  }
}
