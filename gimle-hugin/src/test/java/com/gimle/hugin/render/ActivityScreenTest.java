package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ActivitySnapshot;
import com.gimle.hugin.model.FeedMode;
import com.gimle.hugin.model.FeedRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The activity feed, rendered as strings. */
class ActivityScreenTest {

  private static final Instant NOW = Instant.parse("2026-09-01T14:02:43Z");

  private final ActivityScreen screen = new ActivityScreen(new Painter(ColorMode.NONE));

  @Test
  void every_decision_gets_a_row_naming_who_asked_and_what_of() {
    List<String> lines = render(snapshot(), "", new Viewport(140, 30));

    String row = lineContaining(lines, "alice");
    assertTrue(row.contains("CREATE"), row);
    assertTrue(row.contains("APPLIED"), row);
    assertTrue(row.contains("DEPLOYMENT checkout-api"), row);
    assertTrue(lines.stream().anyMatch(line -> line.startsWith("ACTIVITY")));
  }

  @Test
  void a_refusal_reads_as_bad_in_words_and_is_counted_on_the_status_line() {
    // The reason to open this feed is to find what was turned away, so it has to be legible
    // without colour and countable without reading every row.
    List<String> lines = render(snapshot(), "", new Viewport(140, 30));

    assertTrue(lineContaining(lines, "mallory").contains("DENIED"));
    assertTrue(lineContaining(lines, "bob").contains("REJECTED"));
    assertTrue(lines.getFirst().contains("2 refused"), lines.getFirst());
    assertTrue(lines.getFirst().contains("audit 3"), lines.getFirst());
  }

  @Test
  void a_caller_without_audit_permission_is_told_so_rather_than_shown_an_empty_feed() {
    // An empty feed would read as a quiet cluster, which is the opposite of the truth.
    List<String> lines =
        render(ActivitySnapshot.forbidden("localhost:8080", FeedMode.AUDIT), "", viewport());

    assertTrue(lines.stream().anyMatch(line -> line.contains("does not carry permission")));
    assertFalse(lines.stream().anyMatch(line -> line.contains("nothing recorded yet")));
  }

  @Test
  void an_empty_trail_says_so_rather_than_drawing_a_bare_header() {
    ActivitySnapshot empty =
        new ActivitySnapshot(
            "localhost:8080",
            Optional.of(NOW),
            List.of(),
            FeedMode.AUDIT,
            true,
            Optional.empty(),
            Optional.empty());

    assertTrue(
        render(empty, "", viewport()).stream()
            .anyMatch(line -> line.contains("nothing recorded yet")));
  }

  @Test
  void the_filter_narrows_by_principal_and_by_target() {
    List<String> byPrincipal = render(snapshot(), "mallory", new Viewport(140, 30));
    assertTrue(byPrincipal.stream().anyMatch(line -> line.contains("mallory")));
    assertFalse(byPrincipal.stream().anyMatch(line -> line.contains("alice")));

    List<String> byTarget = render(snapshot(), "checkout", new Viewport(140, 30));
    assertTrue(byTarget.stream().anyMatch(line -> line.contains("alice")));
    assertTrue(lineContaining(byTarget, "ACTIVITY").contains("1 of 3"));
  }

  @Test
  void a_stale_poll_keeps_the_rows_and_says_how_old_they_are() {
    List<String> lines = render(snapshot().stale("connection refused"), "", new Viewport(140, 30));

    assertTrue(lines.getFirst().contains("connection refused"), lines.getFirst());
    assertTrue(lines.stream().anyMatch(line -> line.contains("alice")));
  }

  @Test
  void the_frame_fits_the_viewport_and_ends_in_the_key_bar() {
    // Every feed, because they do not share a column layout: the lifecycle feed spends nine more
    // cells on its state column than the others, which is exactly where a narrow terminal breaks.
    for (FeedMode mode : FeedMode.values()) {
      for (Viewport viewport : List.of(new Viewport(80, 24), new Viewport(200, 50))) {
        List<String> lines = render(feed(mode), "", viewport);

        assertEquals(viewport.rows(), lines.size());
        for (String line : lines) {
          assertTrue(
              Ansi.visibleWidth(line) <= viewport.columns(),
              mode + " line wider than " + viewport.columns() + ": " + line);
        }
        assertTrue(lines.getLast().contains("esc back"), lines.getLast());
        // The feed switch is only discoverable from the key bar, so it has to survive the fit.
        assertTrue(lines.getLast().contains("c feed"), lines.getLast());
      }
    }
  }

  @Test
  void with_colour_switched_off_the_whole_frame_carries_no_escape_sequences() {
    for (String line : render(snapshot(), "", new Viewport(140, 30))) {
      assertFalse(line.contains(Ansi.CSI), "found an escape sequence in: " + line);
    }
  }

  @Test
  void each_feed_names_itself_so_no_reader_has_to_infer_which_record_they_are_in() {
    // The three answer different questions; a feed mistaken for another silently omits exactly
    // what was being looked for.
    assertTrue(
        lineContaining(render(feed(FeedMode.AUDIT), "", wide()), "ACTIVITY")
            .contains("authorization decisions"));
    assertTrue(
        lineContaining(render(feed(FeedMode.LIFECYCLE), "", wide()), "ACTIVITY")
            .contains("lifecycle transitions"));
    assertTrue(
        lineContaining(render(feed(FeedMode.ALERTS), "", wide()), "ACTIVITY")
            .contains("alert rules"));
  }

  @Test
  void the_lifecycle_feed_labels_its_columns_for_instances_rather_than_principals() {
    List<String> lines = render(feed(FeedMode.LIFECYCLE), "", wide());

    String header = lineContaining(lines, "STATE");
    assertTrue(header.contains("INSTANCE"), header);
    assertFalse(header.contains("PRINCIPAL"), header);
  }

  @Test
  void an_alert_rule_shows_no_time_because_it_is_a_standing_declaration_not_an_event() {
    List<String> lines = render(feed(FeedMode.ALERTS), "", wide());

    String header = lineContaining(lines, "STATE");
    assertFalse(header.contains("TIME"), header);
    assertTrue(header.contains("RULE"), header);
    assertTrue(lines.getFirst().contains("firing"), lines.getFirst());
  }

  @Test
  void a_firing_rule_and_a_failed_transition_both_read_as_bad_in_words() {
    assertTrue(
        lineContaining(render(feed(FeedMode.ALERTS), "", wide()), "loud").contains("FIRING"));
    assertTrue(
        lineContaining(render(feed(FeedMode.LIFECYCLE), "", wide()), "checkout")
            .contains("TRANSITION_FAILED"));
  }

  private static Viewport wide() {
    return new Viewport(140, 30);
  }

  private static ActivitySnapshot feed(final FeedMode mode) {
    List<FeedRow> rows =
        switch (mode) {
          case AUDIT -> List.of(row("alice", "CREATE", "APPLIED", "DEPLOYMENT checkout-api"));
          case LIFECYCLE ->
              List.of(row("checkout-api/2", "transition", "TRANSITION_FAILED", "probe timed out"));
          case ALERTS -> List.of(row("loud-rule", "ERROR_RATE", "FIRING", "checkout-api  > 5.0"));
        };
    return new ActivitySnapshot(
        "localhost:8080", Optional.of(NOW), rows, mode, true, Optional.empty(), Optional.empty());
  }

  private List<String> render(
      final ActivitySnapshot snapshot, final String filter, final Viewport viewport) {
    UiState ui = new UiState();
    ui.beginFilter();
    for (char character : filter.toCharArray()) {
      ui.appendToFilter(character);
    }
    ui.commitFilter();
    return screen.render(snapshot, ui, viewport, false, NOW);
  }

  private static Viewport viewport() {
    return new Viewport(140, 30);
  }

  private static String lineContaining(final List<String> lines, final String needle) {
    return lines.stream()
        .filter(line -> line.contains(needle))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no line containing '" + needle + "' in " + lines));
  }

  private static ActivitySnapshot snapshot() {
    return new ActivitySnapshot(
        "localhost:8080",
        Optional.of(NOW),
        List.of(
            row("alice", "CREATE", "APPLIED", "DEPLOYMENT checkout-api"),
            row("bob", "UPDATE", "REJECTED", "TENANT acme"),
            row("mallory", "DELETE", "DENIED", "SECRET db-password")),
        FeedMode.AUDIT,
        true,
        Optional.empty(),
        Optional.empty());
  }

  private static FeedRow row(
      final String actor, final String action, final String verdict, final String subject) {
    return new FeedRow(NOW.minusSeconds(5), actor, action, verdict, subject);
  }
}
