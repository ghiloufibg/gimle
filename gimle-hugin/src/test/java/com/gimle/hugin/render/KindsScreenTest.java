package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.spi.ClusterReader;
import com.gimle.hugin.model.ResourceCatalog;
import com.gimle.hugin.model.ResourceKind;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The list of what {@code :} can open, rendered as strings. */
class KindsScreenTest {

  private final KindsScreen screen = new KindsScreen(new Painter(ColorMode.NONE));

  @Test
  void every_kind_the_catalog_holds_gets_a_row_naming_its_key_and_its_route() {
    // The prompt takes a name, which only helps someone who already knows the names.
    List<String> lines = render(ResourceCatalog.builtInOnly(), new Viewport(140, 40));

    for (ResourceKind kind : ResourceKind.builtIns()) {
      String row =
          lines.stream()
              .filter(line -> line.startsWith(kind.key()))
              .findFirst()
              .orElseThrow(() -> new AssertionError("no row for " + kind.key() + " in " + lines));
      assertTrue(row.contains(kind.route()), row);
    }
  }

  @Test
  void the_count_is_stated_so_a_list_cut_by_a_short_terminal_is_not_read_as_all_of_them() {
    List<String> lines = render(ResourceCatalog.builtInOnly(), new Viewport(140, 12));

    assertTrue(
        lines.getFirst().contains(ResourceKind.builtIns().size() + " kinds"), lines.getFirst());
    assertTrue(lines.stream().anyMatch(line -> line.contains("more than this window holds")));
  }

  @Test
  void a_registered_kind_is_marked_as_one_because_another_cluster_would_not_have_it() {
    List<String> lines =
        render(ResourceCatalog.discover(new GreetingKindReader()), new Viewport(140, 40));

    String row =
        lines.stream()
            .filter(line -> line.startsWith("greetings"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no greetings row in " + lines));
    assertTrue(row.contains("registered kind"), row);
    assertTrue(lines.getFirst().contains("1 registered by this cluster"), lines.getFirst());
  }

  @Test
  void the_frame_fits_the_viewport_and_ends_in_the_key_bar() {
    for (Viewport viewport : List.of(new Viewport(80, 24), new Viewport(200, 50))) {
      List<String> lines = render(ResourceCatalog.builtInOnly(), viewport);

      assertEquals(viewport.rows(), lines.size());
      for (String line : lines) {
        assertTrue(Ansi.visibleWidth(line) <= viewport.columns(), line);
      }
      assertTrue(lines.getLast().contains("esc back"), lines.getLast());
    }
  }

  @Test
  void with_colour_switched_off_the_whole_frame_carries_no_escape_sequences() {
    for (String line : render(ResourceCatalog.builtInOnly(), new Viewport(140, 40))) {
      assertFalse(line.contains(Ansi.CSI), "found an escape sequence in: " + line);
    }
  }

  private List<String> render(final ResourceCatalog catalog, final Viewport viewport) {
    return screen.render(catalog, "localhost:8080", viewport);
  }

  /** A cluster with exactly one kind of its own, so "registered" has something to point at. */
  private static final class GreetingKindReader implements ClusterReader {

    @Override
    public List<Map<String, Object>> getList(final String path) {
      return List.of(
          Map.of(
              "kindName",
              "Greeting",
              "description",
              "a greeting",
              "names",
              Map.of("plural", "greetings", "shortNames", List.of("gr")),
              "printColumns",
              List.of(Map.of("name", "message", "path", "spec.message"))));
    }

    @Override
    public Map<String, Object> getObject(final String path) {
      return Map.of();
    }

    @Override
    public InputStream openStream(final String path) {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public String serverAddress() {
      return "localhost:8080";
    }

    @Override
    public ClusterReader forContext(final String nameOrAddress) {
      throw new UnsupportedOperationException("this reader is not addressed by server");
    }
  }
}
