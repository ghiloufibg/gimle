package com.gimle.holmgang.fenrir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.fenrir.ChaosLedger.Entry;
import com.gimle.holmgang.fenrir.ChaosLedger.Outcome;
import org.junit.jupiter.api.Test;

/** Ledger accounting: executed/recovered/skipped counts and the all-recovered verdict. */
final class ChaosLedgerTest {

  @Test
  void counts_separate_executed_from_skipped() {
    final ChaosLedger ledger = new ChaosLedger(123);
    ledger.record(
        new Entry(1, FaultKind.WORKER_KILL, "greeter#0", 1000, Outcome.RECOVERED, 210, null));
    ledger.record(
        new Entry(2, FaultKind.STORE_BOUNCE, null, 2000, Outcome.SKIPPED, -1, "quorum floor"));
    ledger.record(
        new Entry(
            3, FaultKind.LEADER_BOUNCE, "store-0 (leader)", 3000, Outcome.RECOVERED, 8300, null));

    assertEquals(3, ledger.strikeCount());
    assertEquals(2, ledger.executedCount());
    assertEquals(2, ledger.recoveredCount());
    assertEquals(1, ledger.skippedCount());
    assertTrue(ledger.allRecovered());
    assertEquals(123, ledger.seed());
    assertEquals(1, ledger.executedCountByKind().get(FaultKind.WORKER_KILL));
  }

  @Test
  void a_failed_strike_breaks_the_all_recovered_verdict() {
    final ChaosLedger ledger = new ChaosLedger(1);
    ledger.record(
        new Entry(1, FaultKind.WORKER_KILL, "greeter#0", 1000, Outcome.RECOVERED, 210, null));
    ledger.record(new Entry(2, FaultKind.STORE_BOUNCE, "store-1", 2000, Outcome.FAILED, -1, null));

    assertFalse(ledger.allRecovered());
    assertEquals(2, ledger.executedCount());
    assertEquals(1, ledger.recoveredCount());
  }

  @Test
  void an_empty_ledger_is_vacuously_all_recovered() {
    assertTrue(new ChaosLedger(1).allRecovered());
  }

  @Test
  void render_names_the_seed_and_every_entry() {
    final ChaosLedger ledger = new ChaosLedger(555);
    ledger.record(
        new Entry(1, FaultKind.STORE_BOUNCE, "store-1", 2000, Outcome.RECOVERED, 8300, null));
    final String rendered = ledger.render();
    assertTrue(rendered.contains("555"));
    assertTrue(rendered.contains("STORE_BOUNCE"));
    assertTrue(rendered.contains("store-1"));
  }
}
