package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The line naming a section, which eight screens used to assemble their own way. */
class SectionLabelTest {

  private final Painter painter = new Painter(ColorMode.NONE);

  @Test
  void the_filter_is_drawn_last_however_early_the_screen_mentioned_it() {
    // Eight screens each built this line themselves, and a filter that lands in a different place
    // on each screen is one an operator has to look for rather than glance at.
    String label =
        SectionLabel.of(painter, "scan")
            .filter("checkout")
            .detail("3 findings")
            .alert("1 to fix now", StatusVariant.BAD)
            .build();

    assertTrue(label.indexOf("filter checkout") > label.indexOf("1 to fix now"), label);
  }

  @Test
  void a_blank_filter_adds_nothing() {
    assertFalse(SectionLabel.of(painter, "scan").filter("").build().contains("filter"));
    assertFalse(SectionLabel.of(painter, "scan").filter(null).build().contains("filter"));
  }

  @Test
  void the_section_name_is_drawn_in_upper_case_however_it_was_spelled() {
    assertTrue(SectionLabel.of(painter, "history").build().startsWith("HISTORY"));
  }

  @Test
  void clauses_keep_the_order_the_screen_listed_them_in() {
    // Only the screen knows which of its own qualifiers matters most.
    String label = SectionLabel.of(painter, "as").subject("ops@acme").note("28 kinds").build();

    assertTrue(label.indexOf("ops@acme") < label.indexOf("28 kinds"), label);
  }

  @Test
  void a_detail_sits_closer_to_the_name_than_a_note_does() {
    // The first clause says what the section is; the rest qualify it, and read as separate.
    assertTrue(SectionLabel.of(painter, "scan").detail("x").build().contains("SCAN  x"));
    assertTrue(SectionLabel.of(painter, "scan").note("x").build().contains("SCAN   x"));
  }

  @Test
  void with_colour_switched_off_the_label_carries_no_escape_sequences() {
    String label =
        SectionLabel.of(painter, "xray")
            .detail("service → deployment → instance")
            .alert("2 fronting nothing live", StatusVariant.BAD)
            .filter("api")
            .build();

    assertFalse(label.contains(Ansi.CSI), label);
  }
}
