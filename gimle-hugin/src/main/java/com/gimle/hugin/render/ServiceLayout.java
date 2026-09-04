package com.gimle.hugin.render;

/**
 * Column widths for the service table, derived once from the terminal's width so the header and
 * every row agree on them by construction.
 *
 * <p>The state column is the one width that never flexes: {@code NO ENDPOINTS} is both the longest
 * word this table shows and the only reason to be looking at it, so it is the last thing that may
 * be shortened. The three name columns absorb whatever is left, within bounds.
 */
public record ServiceLayout(
    int name,
    int tenant,
    int port,
    int protocol,
    int state,
    int endpoints,
    int deployments,
    int gap) {

  /** Below this the table switches to tighter columns and single-space gaps. */
  private static final int COMPACT_BELOW = 100;

  private static final int STATE = "NO ENDPOINTS".length();
  private static final int MIN_NAME = 12;
  private static final int MAX_NAME = 28;
  private static final int MIN_TENANT = 6;
  private static final int MAX_TENANT = 16;
  private static final int MIN_DEPLOYMENTS = 10;
  private static final int MAX_DEPLOYMENTS = 40;

  public static ServiceLayout forWidth(final int columns) {
    boolean compact = columns < COMPACT_BELOW;
    int gap = compact ? 1 : 2;
    // Widest at "65535→65535"; the compact width gives up one cell of that, which only a
    // five-digit port pair ever reaches.
    int port = compact ? 10 : 11;
    int protocol = 5;
    int endpoints = 4;
    int flexible = columns - port - protocol - STATE - endpoints - 6 * gap;
    int name = Math.clamp(Math.round(flexible * 0.30f), MIN_NAME, MAX_NAME);
    int tenant = Math.clamp(Math.round(flexible * 0.20f), MIN_TENANT, MAX_TENANT);
    int deployments = Math.clamp(flexible - name - tenant, MIN_DEPLOYMENTS, MAX_DEPLOYMENTS);
    return new ServiceLayout(name, tenant, port, protocol, STATE, endpoints, deployments, gap);
  }
}
