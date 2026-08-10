package com.gimle.core.banner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AnsiPaletteTest {

  private static final String COLOR_PROPERTY = "gimle.color";

  @AfterEach
  void clearProperty() {
    System.clearProperty(COLOR_PROPERTY);
  }

  @Test
  void override_takes_precedence_regardless_of_environment() {
    System.setProperty(COLOR_PROPERTY, "always");
    assertEquals(AnsiPalette.ColorMode.EXTENDED, AnsiPalette.detectMode());

    System.setProperty(COLOR_PROPERTY, "never");
    assertEquals(AnsiPalette.ColorMode.NONE, AnsiPalette.detectMode());
  }

  @Test
  void none_mode_colors_are_all_empty_strings() {
    Map<String, String> colors = AnsiPalette.colorsFor(AnsiPalette.ColorMode.NONE);
    assertTrue(colors.get("C_GOLD").isEmpty());
    assertTrue(colors.get("C_MINT").isEmpty());
    assertTrue(colors.get("C_RED").isEmpty());
    assertTrue(colors.get("C_RESET").isEmpty());
  }

  @Test
  void extended_and_basic_modes_carry_a_real_escape_for_every_named_color() {
    for (AnsiPalette.ColorMode mode :
        new AnsiPalette.ColorMode[] {AnsiPalette.ColorMode.EXTENDED, AnsiPalette.ColorMode.BASIC}) {
      Map<String, String> colors = AnsiPalette.colorsFor(mode);
      for (String key :
          new String[] {"C_GOLD", "C_GOLD_B", "C_MINT", "C_SLATE", "C_RED", "C_RESET"}) {
        assertFalse(colors.get(key).isEmpty(), mode + " missing a color for " + key);
        assertTrue(
            colors.get(key).startsWith("["), mode + "'s " + key + " isn't a real ANSI escape");
      }
    }
  }
}
