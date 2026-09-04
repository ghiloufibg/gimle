package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code printTable}'s own column-presence bug: the header, and every row's own cells, were derived
 * from the first row's key set alone, so a field only a later row carries -- {@code causeSummary}
 * on an {@code events} row that recorded a real failure cause, when the newest event happens to
 * have none -- vanished from the table entirely instead of rendering blank for the rows that lack
 * it.
 */
class OutputFormatTest {

  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);

  private String printed() {
    return buffer.toString(StandardCharsets.UTF_8);
  }

  @Test
  void a_column_only_a_later_row_carries_still_renders_blank_for_the_rows_that_lack_it() {
    Map<String, Object> newest = new LinkedHashMap<>();
    newest.put("id", "evt-2");
    newest.put("kind", "ACTIVE");
    Map<String, Object> older = new LinkedHashMap<>();
    older.put("id", "evt-1");
    older.put("kind", "TRANSITION_FAILED");
    older.put("causeSummary", "IllegalStateException: boom");

    OutputFormat.printList(OutputFormat.Kind.TABLE, List.of(newest, older), out);

    String[] lines = printed().split("\n");
    assertEquals("id\tkind\tcauseSummary", lines[0]);
    assertEquals("evt-2\tACTIVE\t-", lines[1]);
    assertEquals("evt-1\tTRANSITION_FAILED\tIllegalStateException: boom", lines[2]);
  }

  @Test
  void a_column_every_row_shares_renders_exactly_as_before() {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("name", "a");
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("name", "b");

    OutputFormat.printList(OutputFormat.Kind.TABLE, List.of(a, b), out);

    String[] lines = printed().split("\n");
    assertEquals("name", lines[0]);
    assertEquals("a", lines[1]);
    assertEquals("b", lines[2]);
  }
}
