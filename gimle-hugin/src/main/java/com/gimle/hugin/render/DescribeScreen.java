package com.gimle.hugin.render;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ResourceKind;
import com.gimle.hugin.model.ResourceRow;
import java.util.ArrayList;
import java.util.List;

/**
 * One resource in full: exactly the object the collection route answered with, as YAML.
 *
 * <p>It re-reads nothing. The row already carries the object it was built from, so what an operator
 * sees here is the same read the table above it was drawn from -- showing a fresh read instead
 * would let the two disagree and leave them working out which is current.
 *
 * <p>YAML rather than the JSON it arrived as because this is the shape the platform's own manifests
 * are written in, so a field read here is a field an operator can go and change in the manifest
 * they already have. It is a rendering, not a manifest: it carries the status the control plane
 * computes as well as the spec that was submitted, and feeding it back to {@code gimle apply} is
 * not something this pane promises.
 */
public final class DescribeScreen {

  /** Rows the layout spends on everything that isn't a line of the document. */
  private static final int CHROME_ROWS = 5;

  private final Painter painter;

  public DescribeScreen(final Painter painter) {
    this.painter = painter;
  }

  public List<String> render(
      final ResourceKind kind, final ResourceRow row, final UiState ui, final Viewport viewport) {
    List<String> document = Yaml.lines(row.raw());
    int available = Math.max(1, viewport.rows() - CHROME_ROWS);
    int offset = ui.describeOffset(document.size(), available);

    List<String> lines = new ArrayList<>();
    lines.add(title(kind, row, viewport));
    lines.add("");
    lines.add(subtitle(document.size(), offset, available));
    for (int index = offset; index < document.size() && index < offset + available; index++) {
      lines.add(documentLine(document.get(index)));
    }
    return Frame.fitWithKeyBar(lines, StatusBar.describeKeys(painter, viewport), viewport);
  }

  private String title(final ResourceKind kind, final ResourceRow row, final Viewport viewport) {
    return TitleBar.of(painter, kind.key())
        .subject(row.displayName())
        .tenant(row.tenantId())
        .build(viewport);
  }

  private String subtitle(final int total, final int offset, final int available) {
    Line line =
        new Line(painter)
            .add("DESCRIBE", Style.fg(Palette.HUD).asBold())
            .add("  as the control plane answered it", Style.fg(Palette.MUTED_FOREGROUND));
    if (total > available) {
      line.add(
          "   line " + (offset + 1) + "-" + Math.min(total, offset + available) + " of " + total,
          Style.fg(Palette.MUTED));
    }
    return line.build();
  }

  /**
   * A key is muted and its value plain, so the shape of the document reads at a glance without
   * colour carrying anything a no-colour terminal would lose -- the indentation says the same
   * thing. A line with no key is a list element or a continuation and stays plain throughout.
   */
  private String documentLine(final String line) {
    int colon = colonEndingAKey(line);
    if (colon < 0) {
      return new Line(painter).add("  ").add(line).build();
    }
    return new Line(painter)
        .add("  ")
        .add(line.substring(0, colon + 1), Style.fg(Palette.MUTED_FOREGROUND))
        .add(line.substring(colon + 1))
        .build();
  }

  /**
   * Where this line's own key ends, or {@code -1} when it has none. Only a colon before a space or
   * at the end of the line separates a key from a value; one inside a quoted scalar (a timestamp, a
   * host:port) is part of the value and must not be painted as a key.
   */
  private static int colonEndingAKey(final String line) {
    String content = line.stripLeading();
    if (content.startsWith("\"") || content.startsWith("- \"")) {
      return -1;
    }
    int quote = line.indexOf('"');
    for (int index = 0; index < line.length(); index++) {
      if (quote >= 0 && index > quote) {
        return -1;
      }
      if (line.charAt(index) == ':'
          && (index + 1 == line.length() || line.charAt(index + 1) == ' ')) {
        return index;
      }
    }
    return -1;
  }
}
