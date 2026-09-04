package com.gimle.hugin.render;

/**
 * Column widths for the instance table, derived once from the terminal's width so the header and
 * every row agree on them by construction. A width of zero means the column is not drawn at all.
 *
 * <p>The numeric columns are fixed -- they hold numbers whose width is already known -- and the two
 * name columns absorb whatever is left, within bounds: below their minimum the table would stop
 * being readable, and above their maximum a wide terminal would just be spreading two names across
 * a screen of whitespace.
 */
public record InstanceLayout(
    int deployment,
    int tenant,
    int kind,
    int index,
    int node,
    int state,
    int ready,
    int rate,
    int errors,
    int queue,
    int memory,
    int cpu,
    int gap) {

  /** Below this the table switches to tighter numeric columns and single-space gaps. */
  private static final int COMPACT_BELOW = 100;

  /** Below this a tenant column costs more name than it is worth on a single-tenant cluster. */
  private static final int TENANT_BELOW = 140;

  private static final int MIN_DEPLOYMENT = 12;
  private static final int MAX_DEPLOYMENT = 28;
  private static final int MIN_NODE = 8;
  private static final int MAX_NODE = 20;

  public static InstanceLayout forWidth(final int columns) {
    boolean compact = columns < COMPACT_BELOW;
    int gap = compact ? 1 : 2;
    int index = 3;
    // "STATEFUL" is the longest label at 8. A narrow terminal drops the column outright rather
    // than paying for it out of the workload name: the name is what identifies a row, the kind is
    // the same word on most of them, and it stays reachable through the filter and the drill-down.
    int kind = compact ? 0 : 8;
    int tenant = columns < TENANT_BELOW ? 0 : 12;
    // "UNINSTALLED" is the longest lifecycle state at 11; the compact width truncates it, which is
    // the deliberate trade for keeping every metric column on screen at 80.
    int state = compact ? 9 : 11;
    int ready = 3;
    int rate = compact ? 6 : 7;
    int errors = compact ? 6 : 7;
    int queue = compact ? 5 : 6;
    int memory = compact ? 6 : 7;
    int cpu = compact ? 5 : 6;

    int fixed = index + kind + tenant + state + ready + rate + errors + queue + memory + cpu;
    int drawnGaps = 9 + (kind > 0 ? 1 : 0) + (tenant > 0 ? 1 : 0);
    int flexible = columns - fixed - drawnGaps * gap;
    int deployment = Math.clamp(Math.round(flexible * 0.6f), MIN_DEPLOYMENT, MAX_DEPLOYMENT);
    int node = Math.clamp(flexible - deployment, MIN_NODE, MAX_NODE);
    return new InstanceLayout(
        deployment, tenant, kind, index, node, state, ready, rate, errors, queue, memory, cpu, gap);
  }
}
