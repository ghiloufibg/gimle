package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ResourceColumn;
import com.gimle.hugin.model.ResourceKind;
import com.gimle.hugin.model.ResourceRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** One resource in full, rendered as strings. */
class DescribeScreenTest {

  private final DescribeScreen screen = new DescribeScreen(new Painter(ColorMode.NONE));

  @Test
  void the_whole_object_is_shown_including_the_fields_no_column_had_room_for() {
    // The point of this pane is the fields the table could not fit, so it shows the object whole.
    List<String> lines = render(greeting(), new UiState(), wide());

    assertTrue(lines.stream().anyMatch(line -> line.contains("name: hello")), lines.toString());
    assertTrue(lines.stream().anyMatch(line -> line.contains("spec:")), lines.toString());
    assertTrue(lines.stream().anyMatch(line -> line.contains("message: góðan dag")));
    assertTrue(lines.stream().anyMatch(line -> line.contains("generation: 4")));
  }

  @Test
  void the_title_names_the_kind_the_resource_and_the_tenant_that_owns_it() {
    String title = render(greeting(), new UiState(), wide()).getFirst();

    assertTrue(title.contains("GREETINGS"), title);
    assertTrue(title.contains("hello"), title);
    assertTrue(title.contains("tenant acme"), title);
  }

  @Test
  void a_document_taller_than_the_pane_says_which_lines_are_showing() {
    List<String> lines = render(longResource(), new UiState(), new Viewport(140, 12));

    assertTrue(
        lines.stream().anyMatch(line -> line.contains("line 1-") && line.contains(" of ")),
        lines.toString());
  }

  @Test
  void scrolling_moves_the_window_and_never_past_the_end_of_the_document() {
    UiState ui = new UiState();
    Viewport viewport = new Viewport(140, 12);

    ui.scrollDescribe(3);
    assertTrue(shows(render(longResource(), ui, viewport), "line 4-"), "scrolled");

    // Scrolling to the bottom lands on the last page rather than past it into a blank pane.
    ui.scrollDescribeToBottom();
    List<String> bottom = render(longResource(), ui, viewport);
    assertTrue(shows(bottom, "field-30:"), bottom.toString());

    ui.scrollDescribeToTop();
    assertTrue(shows(render(longResource(), ui, viewport), "line 1-"), "back to the top");
  }

  @Test
  void a_document_that_fits_is_not_reported_as_a_window_onto_a_longer_one() {
    List<String> lines = render(greeting(), new UiState(), wide());

    assertFalse(lines.stream().anyMatch(line -> line.contains(" of ")), lines.toString());
  }

  @Test
  void the_frame_fits_the_viewport_and_ends_in_the_key_bar() {
    for (Viewport viewport : List.of(new Viewport(80, 24), new Viewport(200, 50))) {
      List<String> lines = render(longResource(), new UiState(), viewport);

      assertEquals(viewport.rows(), lines.size());
      for (String line : lines) {
        assertTrue(Ansi.visibleWidth(line) <= viewport.columns(), line);
      }
      assertTrue(lines.getLast().contains("esc back"), lines.getLast());
    }
  }

  @Test
  void with_colour_switched_off_the_whole_frame_carries_no_escape_sequences() {
    for (String line : render(greeting(), new UiState(), wide())) {
      assertFalse(line.contains(Ansi.CSI), "found an escape sequence in: " + line);
    }
  }

  private static boolean shows(final List<String> lines, final String needle) {
    return lines.stream().anyMatch(line -> line.contains(needle));
  }

  private List<String> render(final ResourceRow row, final UiState ui, final Viewport viewport) {
    return screen.render(kind(), row, ui, viewport);
  }

  private static Viewport wide() {
    return new Viewport(140, 30);
  }

  private static ResourceKind kind() {
    return ResourceKind.fromDefinition(
        "Greeting",
        "greetings",
        Optional.of("a greeting"),
        List.of(ResourceColumn.of("MESSAGE", "spec.message")));
  }

  private static ResourceRow greeting() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("kind", "Greeting");
    raw.put("name", "hello");
    raw.put("tenantId", "acme");
    raw.put("generation", 4);
    raw.put("spec", Map.of("message", "góðan dag"));
    raw.put("status", null);
    return new ResourceRow("hello", Optional.of("acme"), List.of("hello", "acme"), raw);
  }

  private static ResourceRow longResource() {
    Map<String, Object> raw = new LinkedHashMap<>();
    for (int index = 1; index <= 30; index++) {
      raw.put("field-" + index, "value-" + index);
    }
    return new ResourceRow("long", Optional.empty(), List.of("long"), raw);
  }
}
