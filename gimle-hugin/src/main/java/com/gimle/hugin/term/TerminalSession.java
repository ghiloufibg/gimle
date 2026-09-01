package com.gimle.hugin.term;

import com.gimle.hugin.render.Viewport;
import java.util.Optional;

/**
 * The whole terminal surface the view needs: a size, a keystroke, a way to paint a frame, and a
 * guarantee that whatever was done to the terminal is undone on the way out.
 *
 * <p>Deliberately this small. Everything behind it -- raw mode, key decoding, resize handling -- is
 * the one part of this feature with no meaningful test, so it is kept to the fewest lines that can
 * still do the job, and everything that can live above it does.
 */
public interface TerminalSession extends AutoCloseable {

  /** The terminal's current size, re-read on every call so a resize is picked up for free. */
  Viewport viewport();

  /**
   * The next keystroke, or empty when {@code timeoutMillis} passes with none. A timeout rather than
   * a blocking read is what lets one loop both react to keys immediately and repaint on a clock.
   */
  Optional<Key> readKey(int timeoutMillis);

  /** Paints one frame: the given lines, top to bottom, replacing whatever was there. */
  void paint(java.util.List<String> lines);

  @Override
  void close();
}
