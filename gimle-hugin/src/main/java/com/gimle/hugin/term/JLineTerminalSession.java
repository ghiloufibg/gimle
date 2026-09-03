package com.gimle.hugin.term;

import com.gimle.cli.CliException;
import com.gimle.hugin.render.Ansi;
import com.gimle.hugin.render.Viewport;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * The JLine adapter: raw mode, the alternate screen, key decoding, and putting all of it back on
 * close.
 *
 * <p>JLine is here for exactly one thing the JDK does not offer -- putting a terminal into raw mode
 * -- and the provider selected is the Foreign Function &amp; Memory one, which reaches termios
 * through plain Java downcalls with no JNI and no bundled native library. Selecting it by name
 * rather than letting JLine choose is deliberate: the alternative providers it would otherwise fall
 * back to are the ones this platform does not use.
 *
 * <p>That provider requires native access to be granted to the code calling it, so the launcher
 * passes {@code --enable-native-access}. Without it JLine quietly hands back a dumb terminal
 * instead of failing, which would leave the view drawing escape sequences into a stream that cannot
 * interpret them -- so a dumb terminal is rejected here, loudly, with the reason.
 */
public final class JLineTerminalSession implements TerminalSession {

  private static final String FFM_PROVIDER = "ffm";

  private final Terminal terminal;
  private final PrintWriter writer;
  private final Attributes originalAttributes;

  private JLineTerminalSession(final Terminal terminal) {
    this.terminal = terminal;
    this.writer = terminal.writer();
    this.originalAttributes = terminal.enterRawMode();
    writer.print(Ansi.ENTER_ALT_SCREEN);
    writer.print(Ansi.HIDE_CURSOR);
    writer.flush();
  }

  /** Opens a session, or explains why this terminal cannot host one. */
  public static JLineTerminalSession open() {
    Terminal terminal;
    try {
      terminal = TerminalBuilder.builder().provider(FFM_PROVIDER).system(true).dumb(false).build();
    } catch (IOException | RuntimeException e) {
      throw new CliException(
          "could not open a terminal: "
              + e.getMessage()
              + " (gimle top needs an interactive terminal; pipe-friendly output is what"
              + " 'gimle get nodes' and 'gimle get deployments' are for)",
          e);
    }
    if (Terminal.TYPE_DUMB.equals(terminal.getType())
        || Terminal.TYPE_DUMB_COLOR.equals(terminal.getType())) {
      closeQuietly(terminal);
      throw new CliException(
          "this terminal reports itself as dumb, so gimle top has no way to read keys or draw a"
              + " frame on it");
    }
    // Best-effort: a terminal without mouse support simply keeps working on keys alone, so this
    // is never a reason to refuse a session.
    if (terminal.hasMouseSupport()) {
      terminal.trackMouse(Terminal.MouseTracking.Normal);
    }
    return new JLineTerminalSession(terminal);
  }

  @Override
  public Viewport viewport() {
    return Viewport.of(terminal.getWidth(), terminal.getHeight());
  }

  @Override
  public Optional<Key> readKey(final int timeoutMillis) {
    int first = read(timeoutMillis);
    return switch (first) {
      case -2 -> Optional.empty();
      case -1 -> Optional.of(Key.named(Key.Kind.END_OF_INPUT));
      case 3 -> Optional.of(Key.named(Key.Kind.INTERRUPT));
      case 13, 10 -> Optional.of(Key.named(Key.Kind.ENTER));
      case 127, 8 -> Optional.of(Key.named(Key.Kind.BACKSPACE));
      case 9 -> Optional.of(Key.named(Key.Kind.TAB));
      case 27 -> Optional.of(escapeSequence());
      default -> Optional.of(Key.of((char) first));
    };
  }

  /**
   * Distinguishes a bare {@code esc} from the start of an arrow-key sequence by whether anything
   * follows it immediately. The short poll is the standard way to tell those apart on a terminal
   * that gives no other signal; it is short enough that a real {@code esc} still feels instant.
   */
  private Key escapeSequence() {
    int second = read(30);
    if (second != '[' && second != 'O') {
      return Key.named(Key.Kind.ESCAPE);
    }
    int third = read(30);
    return switch (third) {
      case 'A' -> Key.named(Key.Kind.UP);
      case 'B' -> Key.named(Key.Kind.DOWN);
      case '<' -> mouseSequence();
      // Every other sequence -- left/right, page keys, function keys -- has no binding, and
      // swallowing it is what keeps a stray one from being read as the letters it is made of.
      default -> Key.named(Key.Kind.ESCAPE);
    };
  }

  /**
   * The wheel, and only the wheel, decoded out of an SGR mouse report ({@code ESC [ < b ; x ; y}
   * then {@code M} or {@code m}). A wheel notch is the one mouse gesture that means something
   * unambiguous in a table -- move the cursor -- so it maps onto the arrow keys and needs no new
   * binding. A click would have to be resolved to a row, and the screens hand back a list of
   * strings with no record of which row landed on which line; giving them one to serve a click is a
   * worse trade than not having clicks.
   */
  private Key mouseSequence() {
    StringBuilder report = new StringBuilder();
    int next = read(30);
    while (next >= 0 && next != 'M' && next != 'm' && report.length() < MAX_MOUSE_REPORT) {
      report.append((char) next);
      next = read(30);
    }
    int button;
    try {
      button = Integer.parseInt(report.toString().split(";")[0]);
    } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
      return Key.named(Key.Kind.ESCAPE);
    }
    return switch (button) {
      case WHEEL_UP -> Key.named(Key.Kind.UP);
      case WHEEL_DOWN -> Key.named(Key.Kind.DOWN);
      // A button press or a drag: swallowed, so it never reaches the loop as stray characters.
      default -> Key.named(Key.Kind.ESCAPE);
    };
  }

  private static final int WHEEL_UP = 64;
  private static final int WHEEL_DOWN = 65;

  /** A well-formed SGR report is a handful of digits; anything longer is not one. */
  private static final int MAX_MOUSE_REPORT = 32;

  private int read(final int timeoutMillis) {
    try {
      return terminal.reader().read(timeoutMillis);
    } catch (IOException e) {
      return -1;
    }
  }

  @Override
  public void paint(final List<String> lines) {
    StringBuilder frame = new StringBuilder(Ansi.HOME);
    for (String line : lines) {
      // Clearing to the end of each line as it is written, rather than clearing the whole screen
      // first, is what keeps the frame from flickering: every cell is overwritten exactly once.
      frame.append(line).append(Ansi.CLEAR_TO_LINE_END).append("\r\n");
    }
    frame.append(Ansi.CLEAR_TO_SCREEN_END);
    writer.print(frame);
    writer.flush();
  }

  @Override
  public void close() {
    // Left on, a terminal keeps emitting mouse reports into whatever shell the operator lands
    // back in, which shows up as stray characters at their prompt.
    if (terminal.hasMouseSupport()) {
      terminal.trackMouse(Terminal.MouseTracking.Off);
    }
    writer.print(Ansi.SHOW_CURSOR);
    writer.print(Ansi.EXIT_ALT_SCREEN);
    writer.print(Ansi.RESET);
    writer.flush();
    terminal.setAttributes(originalAttributes);
    closeQuietly(terminal);
  }

  private static void closeQuietly(final Terminal terminal) {
    try {
      terminal.close();
    } catch (IOException ignored) {
      // Nothing useful is left to do about a terminal that will not close.
    }
  }
}
