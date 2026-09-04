package com.gimle.hugin.render;

import java.util.ArrayList;
import java.util.List;

/**
 * The last thing every screen's lines pass through: cut to the terminal's own width and height.
 *
 * <p>A single choke point rather than a budget calculation at each call site that interpolates text
 * it does not control -- a typed filter, a control plane's own failure message, a long deployment
 * name. One of those getting its arithmetic wrong wraps a line, which shifts every row below it and
 * makes the whole frame look broken.
 */
final class Frame {

  private Frame() {}

  static List<String> fit(final List<String> lines, final Viewport viewport) {
    List<String> cut =
        lines.stream().map(line -> Ansi.truncateVisible(line, viewport.columns())).toList();
    return cut.size() > viewport.rows() ? cut.subList(0, viewport.rows()) : cut;
  }

  /**
   * Pads or cuts {@code body} to everything above the terminal's last row, then puts {@code keyBar}
   * on that row. The bar is the one line that has to survive a short terminal: cutting the panes to
   * keep it beats a frame that ends wherever a pane happened to reach the bottom, leaving an
   * operator no visible way out of the view.
   */
  static List<String> fitWithKeyBar(
      final List<String> body, final String keyBar, final Viewport viewport) {
    int bodyRows = Math.max(0, viewport.rows() - 1);
    List<String> frame = new ArrayList<>(body.subList(0, Math.min(body.size(), bodyRows)));
    while (frame.size() < bodyRows) {
      frame.add("");
    }
    frame.add(keyBar);
    return fit(frame, viewport);
  }
}
