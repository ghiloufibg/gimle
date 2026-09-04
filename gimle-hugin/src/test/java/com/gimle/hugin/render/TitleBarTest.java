package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hugin.UiState;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The one bar every screen draws.
 *
 * <p>What is pinned here is the order and the wording, because that is what ten screens assembling
 * their own bar got wrong: a status line that reads differently depending on which screen you are
 * on is worse than a plain one, since the difference looks like it means something.
 */
class TitleBarTest {

  private static final Viewport WIDE = new Viewport(200, 30);

  private final Painter painter = new Painter(ColorMode.NONE);

  @Test
  void the_bar_reads_left_to_right_as_the_question_an_operator_is_asking() {
    String bar =
        TitleBar.of(painter, "services")
            .subject("localhost:8080")
            .connection(true, Optional.empty(), Optional.empty())
            .stat("services", 4)
            .badge("2 unresolved", StatusVariant.BAD)
            .paused(true)
            .build(WIDE);

    assertOrder(
        bar,
        "GIMLÉ",
        "SERVICES",
        "localhost:8080",
        "connected",
        "services 4",
        "2 unresolved",
        "PAUSED");
  }

  @Test
  void a_screen_name_is_drawn_in_upper_case_however_it_was_spelled() {
    // Half the screens named themselves in lower case and half in upper, which read as a
    // distinction where there was none.
    assertTrue(TitleBar.of(painter, "history").build(WIDE).contains("HISTORY"));
    assertTrue(TitleBar.of(painter, "PULSE").build(WIDE).contains("PULSE"));
  }

  @Test
  void the_cluster_view_names_no_screen_because_it_is_the_one_you_return_to() {
    String bar = TitleBar.unnamed(painter).subject("localhost:8080").build(WIDE);

    assertTrue(bar.contains("GIMLÉ TOP   localhost:8080"), bar);
  }

  @Test
  void a_stale_reading_says_why_and_how_old_it_is_rather_than_going_blank() {
    String bar =
        TitleBar.of(painter, "nodes")
            .connection(
                false, Optional.of("connection refused"), Optional.of(Duration.ofSeconds(8)))
            .build(WIDE);

    assertTrue(bar.contains("connection refused 8s old"), bar);
  }

  @Test
  void a_reading_that_never_landed_says_disconnected_rather_than_naming_an_age_it_does_not_have() {
    String bar =
        TitleBar.of(painter, "nodes")
            .connection(false, Optional.empty(), Optional.empty())
            .build(WIDE);

    assertTrue(bar.contains("disconnected"), bar);
    assertFalse(bar.contains("old"), bar);
  }

  @Test
  void the_tenant_in_scope_is_named_before_any_count_it_narrows() {
    // A cluster showing one tenant's three instances is otherwise indistinguishable from a
    // cluster that has only three.
    UiState ui = new UiState();
    ui.scopeToTenant("acme");

    assertOrder(
        TitleBar.unnamed(painter).scope(ui).stat("instances", 3).build(WIDE),
        "tenant acme",
        "instances 3");
  }

  @Test
  void no_tenant_in_scope_adds_nothing_to_the_bar() {
    assertFalse(TitleBar.unnamed(painter).scope(new UiState()).build(WIDE).contains("tenant"));
  }

  @Test
  void the_paused_marker_is_last_wherever_it_was_set() {
    // It is a property of the screen rather than of anything on it, and an operator scanning for
    // why nothing is moving looks at the end of the line.
    String bar =
        TitleBar.of(painter, "pulse")
            .paused(true)
            .stat("nodes", 2)
            .badge("down", StatusVariant.BAD)
            .build(WIDE);

    assertOrder(bar, "nodes 2", "down", "PAUSED");
  }

  @Test
  void the_health_split_keeps_a_colour_per_count_and_still_reads_without_one() {
    assertTrue(TitleBar.unnamed(painter).health(3, 1, 2).build(WIDE).contains("3/1/2"));
  }

  @Test
  void the_bar_fills_the_terminal_rather_than_stopping_after_its_text() {
    // What makes the card colour run the full width instead of ending mid-line.
    String bar = TitleBar.of(painter, "pulse").subject("localhost:8080").build(WIDE);

    assertEquals(WIDE.columns(), Ansi.visibleWidth(bar), bar);
  }

  @Test
  void a_bar_longer_than_the_terminal_is_left_for_the_frame_to_cut() {
    // Cutting is one choke point rather than a budget calculation in every bar; doing it here too
    // would be a second place to get it wrong.
    Viewport narrow = new Viewport(40, 24);
    String bar =
        TitleBar.of(painter, "permissions")
            .subject("a-very-long-control-plane-address.example.internal:8443")
            .stat("kinds", 28)
            .build(narrow);

    assertTrue(Ansi.visibleWidth(bar) > narrow.columns(), bar);
    assertEquals(narrow.columns(), Ansi.visibleWidth(Frame.fit(List.of(bar), narrow).getFirst()));
  }

  @Test
  void with_colour_switched_off_the_bar_carries_no_escape_sequences() {
    String bar =
        TitleBar.unnamed(painter)
            .connection(true, Optional.empty(), Optional.empty())
            .health(1, 0, 1)
            .badge("unplaced 2", StatusVariant.WARN)
            .build(WIDE);

    assertFalse(bar.contains(Ansi.CSI), bar);
  }

  /** Asserts each needle appears, and that each appears after the one before it. */
  private static void assertOrder(final String bar, final String... needles) {
    int previous = -1;
    for (String needle : needles) {
      int at = bar.indexOf(needle);
      assertTrue(at >= 0, needle + " missing from: " + bar);
      assertTrue(at > previous, needle + " out of order in: " + bar);
      previous = at;
    }
  }
}
