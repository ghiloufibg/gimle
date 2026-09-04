package com.gimle.hugin.render;

import java.util.List;

/**
 * Reading a rendered frame in a test.
 *
 * <p>Exists because of one repeated mistake: the title bar names the screen, so searching a whole
 * frame for the screen's own name finds the bar rather than the section label under it, and the
 * assertion then quietly tests the wrong line. Every lookup here skips the bar, and the bar itself
 * is asked for by name.
 */
final class Frames {

  private Frames() {}

  /** The bar across the top, which every screen draws first. */
  static String titleBar(final List<String> lines) {
    return lines.getFirst();
  }

  /** Everything below the title bar. */
  static List<String> body(final List<String> lines) {
    return lines.subList(1, lines.size());
  }

  /** The first line below the title bar containing {@code needle}. */
  static String lineContaining(final List<String> lines, final String needle) {
    return body(lines).stream()
        .filter(line -> line.contains(needle))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("no line below the title bar contains '" + needle + "'"));
  }

  /** Where {@code needle} first appears below the title bar, as an index into the whole frame. */
  static int indexOfLine(final List<String> lines, final String needle) {
    List<String> body = body(lines);
    for (int index = 0; index < body.size(); index++) {
      if (body.get(index).contains(needle)) {
        return index + 1;
      }
    }
    throw new AssertionError("no line below the title bar contains '" + needle + "' in " + lines);
  }
}
