package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Column arithmetic. This is the piece everything else in the view rests on: get the visible width
 * of a coloured run wrong and every table below it shears by a few cells.
 */
class LineTest {

  private final Painter coloured = new Painter(ColorMode.TRUECOLOR);
  private final Painter plain = new Painter(ColorMode.NONE);

  @Test
  void a_coloured_cell_occupies_exactly_the_same_columns_as_a_plain_one() {
    String withColour =
        new Line(coloured).cell("ACTIVE", 12, Style.fg(Palette.OK)).add("|").build();
    String without = new Line(plain).cell("ACTIVE", 12, Style.fg(Palette.OK)).add("|").build();

    assertEquals(12, Ansi.visibleWidth(withColour) - 1);
    assertEquals(Ansi.visibleWidth(without), Ansi.visibleWidth(withColour));
    assertTrue(withColour.contains(Ansi.CSI), "the coloured one should still carry a sequence");
  }

  @Test
  void a_value_longer_than_its_cell_is_truncated_with_an_ellipsis_rather_than_wrapped() {
    String line = new Line(plain).cell("a-very-long-deployment-name", 12, Style.PLAIN).build();

    assertEquals("a-very-long…", line);
    assertEquals(12, line.length());
  }

  @Test
  void a_numeric_cell_right_aligns_within_its_width() {
    assertEquals("   142Mi", new Line(plain).rightCell("142Mi", 8, Style.PLAIN).build());
  }

  @Test
  void pad_to_lands_at_the_same_column_whether_earlier_cells_were_coloured_or_not() {
    String line =
        new Line(coloured).add("NODES", Style.fg(Palette.HUD).asBold()).padTo(20).add("x").build();

    assertEquals(21, Ansi.visibleWidth(line));
  }

  @Test
  void fill_to_runs_the_bar_background_out_to_the_full_terminal_width() {
    String line =
        new Line(coloured)
            .add(" GIMLÉ TOP", Style.fg(Palette.PRIMARY).on(Palette.CARD))
            .fillTo(80, Style.fg(Palette.MUTED_FOREGROUND).on(Palette.CARD))
            .build();

    assertEquals(80, Ansi.visibleWidth(line));
  }

  @Test
  void a_cell_of_zero_or_negative_width_contributes_nothing() {
    assertEquals("", new Line(plain).cell("value", 0, Style.PLAIN).build());
    assertEquals("", new Line(plain).rightCell("value", -4, Style.PLAIN).build());
  }

  @Test
  void a_line_wider_than_the_terminal_is_cut_to_it_with_its_styling_closed_off() {
    String line =
        new Line(coloured)
            .add("could not reach control plane at http://127.0.0.1:8080", Style.fg(Palette.WARN))
            .build();

    String cut = Ansi.truncateVisible(line, 20);

    assertEquals(20, Ansi.visibleWidth(cut));
    assertTrue(cut.endsWith(Ansi.RESET), "a cut inside a styled run must reset: " + cut);
  }

  @Test
  void a_line_that_already_fits_is_returned_untouched() {
    String line = new Line(plain).add("short").build();

    assertEquals(line, Ansi.truncateVisible(line, 40));
  }

  @Test
  void a_gauge_always_occupies_its_full_width_however_full_it_reads() {
    for (double fraction : new double[] {0.0, 0.01, 0.5, 1.0}) {
      Line line = new Line(coloured);
      Gauge.draw(line, fraction, 8);
      assertEquals(8, line.width(), "fraction " + fraction);
    }
  }

  @Test
  void a_small_but_real_reading_still_shows_one_filled_cell() {
    Line line = new Line(plain);
    Gauge.draw(line, 0.01, 8);

    assertTrue(line.build().startsWith("▇"), line.build());
  }
}
