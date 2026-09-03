package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hugin.model.ActivityRow;
import com.gimle.hugin.model.ActivitySnapshot;
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
    assertTrue(lines.getFirst().contains("decisions 3"), lines.getFirst());
  }

  @Test
  void a_caller_without_audit_permission_is_told_so_rather_than_shown_an_empty_feed() {
    // An empty feed would read as a quiet cluster, which is the opposite of the truth.
    List<String> lines = render(ActivitySnapshot.forbidden("localhost:8080"), "", viewport());

    assertTrue(lines.stream().anyMatch(line -> line.contains("does not carry permission")));
    assertFalse(lines.stream().anyMatch(line -> line.contains("nothing recorded yet")));
  }

  @Test
  void an_empty_trail_says_so_rather_than_drawing_a_bare_header() {
    ActivitySnapshot empty =
        new ActivitySnapshot("localhost:8080", Optional.of(NOW), List.of(), true, Optional.empty());

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
    for (Viewport viewport : List.of(new Viewport(80, 24), new Viewport(200, 50))) {
      List<String> lines = render(snapshot(), "", viewport);

      assertEquals(viewport.rows(), lines.size());
      for (String line : lines) {
        assertTrue(
            Ansi.visibleWidth(line) <= viewport.columns(),
            "line wider than " + viewport.columns() + ": " + line);
      }
      assertTrue(lines.getLast().contains("esc back"), lines.getLast());
    }
  }

  @Test
  void with_colour_switched_off_the_whole_frame_carries_no_escape_sequences() {
    for (String line : render(snapshot(), "", new Viewport(140, 30))) {
      assertFalse(line.contains(Ansi.CSI), "found an escape sequence in: " + line);
    }
  }

  private List<String> render(
      final ActivitySnapshot snapshot, final String filter, final Viewport viewport) {
    return screen.render(snapshot, filter, viewport, false, NOW);
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
            event("alice", "CREATE", "DEPLOYMENT", "checkout-api", true, "APPLIED"),
            event("bob", "UPDATE", "TENANT", "acme", true, "REJECTED"),
            event("mallory", "DELETE", "SECRET", "db-password", false, "REJECTED")),
        true,
        Optional.empty());
  }

  private static ActivityRow event(
      final String principal,
      final String verb,
      final String kind,
      final String target,
      final boolean allowed,
      final String outcome) {
    return new ActivityRow(
        principal,
        kind,
        verb,
        Optional.empty(),
        Optional.of(target),
        allowed,
        outcome,
        NOW.minusSeconds(5));
  }
}
