package com.gimle.ivaldi.run;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A run's append-only log, read back with a cursor the same way {@code
 * com.gimle.agent.LogFileReader}'s day-file cursors work: a client remembers the last cursor it saw
 * and asks for everything past it, so a poll never re-reads lines it already has and never misses
 * one appended between polls. Kept in memory only -- a run's log does not need to survive an {@code
 * IvaldiMain} restart, unlike a deployed cluster's own logs.
 */
final class RunLog {

  /** One already-read page: the lines past the requested cursor, and the cursor to ask for next. */
  record Page(List<String> lines, int nextCursor) {}

  private final List<String> lines = new ArrayList<>();

  synchronized void append(String line) {
    lines.add("[" + Instant.now() + "] " + line);
  }

  synchronized Page since(int cursor) {
    int from = Math.max(0, Math.min(cursor, lines.size()));
    return new Page(List.copyOf(lines.subList(from, lines.size())), lines.size());
  }
}
