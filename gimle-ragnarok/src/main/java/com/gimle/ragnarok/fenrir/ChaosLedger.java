package com.gimle.ragnarok.fenrir;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The immutable record of one soak: one {@link Entry} per scheduled strike, in fire order, whether
 * it recovered, was skipped by a safety guard, or failed its recovery gate. It is both the
 * assertion surface ({@link #executedCount()}, {@link #allRecovered()}) and what gets rendered in
 * full into any forensic report the soak produces -- skips carry their reason so nothing is
 * silently dropped.
 */
public final class ChaosLedger {

  /** The outcome of one scheduled strike. */
  public enum Outcome {
    /** The fault fired and the cluster met every recovery gate within the deadline. */
    RECOVERED,
    /** A safety guard refused the fault before it fired; {@link Entry#skipReason} says why. */
    SKIPPED,
    /** The fault fired but a recovery gate missed its deadline; the soak stops here. */
    FAILED
  }

  /**
   * One scheduled strike. {@code victim} is null for a skip that never resolved a target; {@code
   * recoveryMillis} is -1 unless the outcome is {@link Outcome#RECOVERED}; {@code skipReason} is
   * null unless the outcome is {@link Outcome#SKIPPED}.
   */
  public record Entry(
      int index,
      FaultKind kind,
      String victim,
      long firedAtOffsetMillis,
      Outcome outcome,
      long recoveryMillis,
      String skipReason) {}

  private final long seed;
  private final List<Entry> entries = new ArrayList<>();

  ChaosLedger(final long seed) {
    this.seed = seed;
  }

  void record(final Entry entry) {
    entries.add(entry);
  }

  public long seed() {
    return seed;
  }

  public List<Entry> entries() {
    return List.copyOf(entries);
  }

  /** Every scheduled strike, executed or skipped. */
  public int strikeCount() {
    return entries.size();
  }

  /** Strikes that actually fired a fault (recovered or failed) -- skips excluded. */
  public int executedCount() {
    return (int) entries.stream().filter(e -> e.outcome() != Outcome.SKIPPED).count();
  }

  public int recoveredCount() {
    return (int) entries.stream().filter(e -> e.outcome() == Outcome.RECOVERED).count();
  }

  public int skippedCount() {
    return (int) entries.stream().filter(e -> e.outcome() == Outcome.SKIPPED).count();
  }

  /** True when every fault that fired recovered -- vacuously true if nothing fired. */
  public boolean allRecovered() {
    return entries.stream()
        .filter(e -> e.outcome() != Outcome.SKIPPED)
        .allMatch(e -> e.outcome() == Outcome.RECOVERED);
  }

  public Map<FaultKind, Integer> executedCountByKind() {
    final Map<FaultKind, Integer> counts = new EnumMap<>(FaultKind.class);
    for (final Entry entry : entries) {
      if (entry.outcome() != Outcome.SKIPPED) {
        counts.merge(entry.kind(), 1, Integer::sum);
      }
    }
    return counts;
  }

  /** A compact human-readable summary for the scenario log and forensic reports. */
  public String render() {
    final StringBuilder out = new StringBuilder();
    out.append("Fenrir chaos ledger (seed ")
        .append(seed)
        .append("): ")
        .append(executedCount())
        .append(" executed, ")
        .append(recoveredCount())
        .append(" recovered, ")
        .append(skippedCount())
        .append(" skipped\n");
    for (final Entry entry : entries) {
      out.append(String.format("  #%-2d %-20s ", entry.index(), entry.kind()));
      out.append(String.format("t+%-6dms ", entry.firedAtOffsetMillis()));
      out.append(entry.outcome());
      if (entry.victim() != null) {
        out.append(" victim=").append(entry.victim());
      }
      if (entry.outcome() == Outcome.RECOVERED) {
        out.append(" in ").append(entry.recoveryMillis()).append("ms");
      }
      if (entry.skipReason() != null) {
        out.append(" (").append(entry.skipReason()).append(")");
      }
      out.append('\n');
    }
    return out.toString();
  }
}
