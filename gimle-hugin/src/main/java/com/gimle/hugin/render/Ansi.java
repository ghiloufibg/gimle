package com.gimle.hugin.render;

/**
 * The handful of control sequences the view writes directly.
 *
 * <p>The escape byte is built from its code point rather than written as a {@code \\u001B} literal:
 * Java processes unicode escapes in the lexer, before string literals exist, so a raw one here
 * would leave an invisible control character sitting in the source for every future reader and diff
 * to trip over.
 */
public final class Ansi {

  private static final char ESCAPE = 27;

  /** Control Sequence Introducer. */
  public static final String CSI = ESCAPE + "[";

  /** Reset every attribute set by a previous SGR sequence. */
  public static final String RESET = CSI + "0m";

  /** Switch to the alternate screen buffer, so quitting leaves the scrollback untouched. */
  public static final String ENTER_ALT_SCREEN = CSI + "?1049h";

  /** Return to the normal screen buffer. */
  public static final String EXIT_ALT_SCREEN = CSI + "?1049l";

  public static final String HIDE_CURSOR = CSI + "?25l";

  public static final String SHOW_CURSOR = CSI + "?25h";

  /** Move the cursor to the top-left cell. */
  public static final String HOME = CSI + "H";

  /** Erase from the cursor to the end of the current line. */
  public static final String CLEAR_TO_LINE_END = CSI + "K";

  /** Erase from the cursor to the end of the screen. */
  public static final String CLEAR_TO_SCREEN_END = CSI + "J";

  private Ansi() {}

  /**
   * {@code text} cut to {@code columns} visible cells, escape sequences preserved and a reset
   * appended when the cut lands inside a styled run. What guarantees no rendered line can ever
   * exceed the terminal's width and wrap: the alternative is every call site that interpolates
   * operator- or server-supplied text (a filter, a failure message, a deployment name) getting its
   * own budget arithmetic right, forever.
   */
  public static String truncateVisible(final String text, final int columns) {
    if (columns <= 0) {
      return "";
    }
    if (visibleWidth(text) <= columns) {
      return text;
    }
    StringBuilder cut = new StringBuilder();
    int width = 0;
    int index = 0;
    while (index < text.length() && width < columns) {
      if (text.startsWith(CSI, index)) {
        int end = index + CSI.length();
        while (end < text.length() && text.charAt(end) != 'm') {
          end++;
        }
        cut.append(text, index, Math.min(end + 1, text.length()));
        index = end + 1;
        continue;
      }
      cut.append(text.charAt(index));
      index++;
      width++;
    }
    return cut.append(RESET).toString();
  }

  /**
   * The visible width of {@code text}: its length with every SGR sequence discounted. Column
   * arithmetic has to be done on this, never on {@code String.length()}, or a coloured cell pads to
   * a different width than a plain one and the whole table shears.
   */
  public static int visibleWidth(final String text) {
    int width = 0;
    int index = 0;
    while (index < text.length()) {
      if (text.startsWith(CSI, index)) {
        int end = index + CSI.length();
        while (end < text.length() && text.charAt(end) != 'm') {
          end++;
        }
        index = end + 1;
        continue;
      }
      width++;
      index++;
    }
    return width;
  }
}
