package com.gimle.hugin.render;

/**
 * Column widths for the node table. The two resource columns hold a reading and a bar side by side,
 * so they are the widest thing here and the first to give up cells on a narrow terminal.
 */
public record NodeLayout(int id, int state, int cpu, int memory, int instances, int heartbeat) {

  private static final int COMPACT_BELOW = 100;
  private static final int MIN_ID = 10;
  private static final int MAX_ID = 24;

  public static NodeLayout forWidth(final int columns) {
    boolean compact = columns < COMPACT_BELOW;
    int state = 8;
    int cpu = compact ? 18 : 22;
    int memory = compact ? 18 : 22;
    int instances = 4;
    int heartbeat = 9;
    int used = state + cpu + memory + instances + heartbeat + 5 * 2;
    int id = Math.clamp(columns - used, MIN_ID, MAX_ID);
    return new NodeLayout(id, state, cpu, memory, instances, heartbeat);
  }
}
