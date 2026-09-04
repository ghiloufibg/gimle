package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Colour depth, and the promise that the lowest one emits nothing at all. */
class PainterTest {

  @Test
  void truecolor_emits_the_tokens_exact_srgb_value() {
    String painted = new Painter(ColorMode.TRUECOLOR).paint("ACTIVE", Style.fg(Palette.OK));

    assertEquals(Ansi.CSI + "38;2;74;226;172mACTIVE" + Ansi.RESET, painted);
  }

  @Test
  void a_background_and_bold_travel_in_one_sequence_alongside_the_foreground() {
    String painted =
        new Painter(ColorMode.TRUECOLOR)
            .paint("TOP", Style.fg(Palette.PRIMARY).on(Palette.CARD).asBold());

    assertEquals(Ansi.CSI + "1;38;2;57;215;176;48;2;14;35;47mTOP" + Ansi.RESET, painted);
  }

  @Test
  void the_256_colour_mode_approximates_a_token_into_the_cube() {
    String painted = new Painter(ColorMode.ANSI256).paint("ACTIVE", Style.fg(Palette.OK));

    assertTrue(painted.startsWith(Ansi.CSI + "38;5;"), painted);
    assertTrue(painted.endsWith("mACTIVE" + Ansi.RESET), painted);
  }

  @Test
  void a_near_neutral_token_lands_on_the_grey_ramp_rather_than_being_tinted_by_the_cube() {
    String painted = new Painter(ColorMode.ANSI256).paint("x", Style.fg(Palette.MUTED));

    int index = colorIndex(painted);
    assertTrue(index >= 232 && index <= 255, "expected a grey-ramp index, got " + index);
  }

  @Test
  void no_colour_mode_emits_not_one_escape_byte() {
    Painter painter = new Painter(ColorMode.NONE);

    String painted = painter.paint("FAILED", Style.fg(Palette.BAD).on(Palette.CARD).asBold());

    assertEquals("FAILED", painted);
    assertFalse(painted.contains(Ansi.CSI));
  }

  @Test
  void a_plain_style_is_never_wrapped_in_a_sequence_even_at_full_colour_depth() {
    assertEquals("plain", new Painter(ColorMode.TRUECOLOR).paint("plain", Style.PLAIN));
  }

  @Test
  void no_colour_is_chosen_for_a_non_tty_a_dumb_term_and_an_explicit_no_color() {
    assertEquals(ColorMode.NONE, ColorMode.detect(false, null, "truecolor", "xterm-256color"));
    assertEquals(ColorMode.NONE, ColorMode.detect(true, "1", "truecolor", "xterm-256color"));
    assertEquals(ColorMode.NONE, ColorMode.detect(true, null, null, "dumb"));
  }

  @Test
  void a_terminal_that_advertises_truecolor_gets_it_and_everything_else_settles_for_256() {
    assertEquals(ColorMode.TRUECOLOR, ColorMode.detect(true, null, "truecolor", "xterm"));
    assertEquals(ColorMode.TRUECOLOR, ColorMode.detect(true, null, "24bit", "xterm"));
    assertEquals(ColorMode.ANSI256, ColorMode.detect(true, null, null, "xterm-256color"));
    // NO_COLOR's own convention: the variable being set at all is what counts, but an empty value
    // is treated as unset, which is what its specification says.
    assertEquals(ColorMode.ANSI256, ColorMode.detect(true, "", null, "xterm"));
  }

  private static int colorIndex(final String painted) {
    String body = painted.substring((Ansi.CSI + "38;5;").length());
    return Integer.parseInt(body.substring(0, body.indexOf('m')));
  }
}
