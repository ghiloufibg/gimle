package com.gimle.hugin;

import com.gimle.cli.CliException;
import com.gimle.cli.spi.CliExtension;
import com.gimle.cli.spi.ClusterReader;
import com.gimle.hugin.render.ColorMode;
import com.gimle.hugin.render.Painter;
import com.gimle.hugin.term.JLineTerminalSession;
import com.gimle.hugin.term.TerminalSession;
import java.io.PrintStream;
import java.util.List;

/**
 * {@code gimle top}: the whole feature's entry point, and the only class the CLI ever names.
 *
 * <p>Discovered through {@code META-INF/services} on the classpath, which is how the shipped CLI
 * loads code, and additionally declared in this module's {@code module-info} for the day something
 * runs it modular. Take this jar off the path and {@code gimle top} goes back to being the
 * unknown-verb error it was before -- there is nothing else to unwind.
 */
public final class HuginExtension implements CliExtension {

  @Override
  public String verb() {
    return "top";
  }

  @Override
  public String usageLine() {
    return "top                     (live cluster view; read-only, q to quit)";
  }

  @Override
  public void run(final List<String> args, final ClusterReader reader, final PrintStream out) {
    if (!args.isEmpty()) {
      throw new CliException("usage: gimle top   (no arguments; press ? in the view for keys)");
    }
    Painter painter = new Painter(detectColorMode());
    try (TerminalSession terminal = JLineTerminalSession.open()) {
      new Hugin(reader, terminal, painter).run();
    }
  }

  /**
   * A verb that takes over the whole terminal is only ever run interactively, so the TTY question
   * is settled by whether one is attached at all -- {@code System.console()} answers exactly that,
   * with no dependency of its own.
   */
  private static ColorMode detectColorMode() {
    return ColorMode.detect(
        System.console() != null,
        System.getenv("NO_COLOR"),
        System.getenv("COLORTERM"),
        System.getenv("TERM"));
  }
}
