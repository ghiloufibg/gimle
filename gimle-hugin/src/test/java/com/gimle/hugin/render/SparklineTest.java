package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The block-glyph line the drill-down draws a meter's history as. */
class SparklineTest {

  private static final int CELLS = 8;

  @Test
  void a_series_scales_against_zero_so_its_peak_fills_the_column_and_nothing_reads_as_empty() {
    assertEquals("▁▃▅▆█", draw(List.of(0.0, 25.0, 50.0, 75.0, 100.0), 5));
  }

  @Test
  void a_series_shorter_than_the_line_still_occupies_the_whole_width() {
    String drawn = draw(List.of(1.0, 2.0), CELLS);

    assertEquals(CELLS, drawn.length());
    // Right-aligned: the newest reading lands in the last column whatever the series' length, so
    // two rows drawn side by side agree on where "now" is.
    assertEquals("      ▅█", drawn);
  }

  @Test
  void a_series_longer_than_the_line_keeps_its_newest_readings_and_drops_the_oldest() {
    String drawn = draw(List.of(100.0, 100.0, 100.0, 0.0, 1.0), 2);

    assertEquals("▁█", drawn);
  }

  @Test
  void an_empty_series_occupies_the_full_width_rather_than_collapsing_the_columns_after_it() {
    assertEquals(" ".repeat(CELLS), draw(List.of(), CELLS));
  }

  @Test
  void an_all_zero_series_draws_the_lowest_glyph_rather_than_nothing_at_all() {
    assertEquals("▁▁▁", draw(List.of(0.0, 0.0, 0.0), 3));
  }

  @Test
  void a_line_of_no_width_draws_nothing_and_costs_no_columns() {
    assertEquals("", draw(List.of(1.0, 2.0), 0));
  }

  @Test
  void the_whole_run_takes_one_status_colour_rather_than_one_per_glyph() {
    Line line = new Line(new Painter(ColorMode.TRUECOLOR));
    Sparkline.draw(line, List.of(1.0, 2.0, 3.0), 3, StatusVariant.BAD);
    String drawn = line.build();

    assertEquals(1, occurrences(drawn, Ansi.CSI + "38;"), drawn);
    assertEquals(3, Ansi.visibleWidth(drawn), drawn);
  }

  @Test
  void with_colour_switched_off_it_emits_no_escape_sequences_at_all() {
    String drawn = draw(List.of(1.0, 5.0, 9.0), CELLS);

    assertFalse(drawn.contains(Ansi.CSI), drawn);
    assertTrue(drawn.endsWith("▂▅█"), drawn);
  }

  private static String draw(final List<Double> values, final int cells) {
    Line line = new Line(new Painter(ColorMode.NONE));
    Sparkline.draw(line, values, cells, StatusVariant.INFO);
    return line.build();
  }

  private static int occurrences(final String text, final String needle) {
    int count = 0;
    int index = text.indexOf(needle);
    while (index >= 0) {
      count++;
      index = text.indexOf(needle, index + needle.length());
    }
    return count;
  }
}
