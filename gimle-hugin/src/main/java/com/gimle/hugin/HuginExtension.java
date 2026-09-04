package com.gimle.hugin;

import com.gimle.cli.CliException;
import com.gimle.cli.spi.CliExtension;
import com.gimle.cli.spi.ClusterReader;
import com.gimle.hugin.render.ColorMode;
import com.gimle.hugin.render.Painter;
import com.gimle.hugin.term.JLineTerminalSession;
import com.gimle.hugin.term.TerminalSession;
import java.io.PrintStream;
import java.time.Duration;
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

  private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(2);
  private static final long MIN_INTERVAL_SECONDS = 1;
  private static final long MAX_INTERVAL_SECONDS = 60;

  @Override
  public String verb() {
    return "top";
  }

  @Override
  public String usageLine() {
    return "top [--interval=SECS]   (live cluster view; read-only, q to quit)";
  }

  @Override
  public void run(final List<String> args, final ClusterReader reader, final PrintStream out) {
    Duration interval = parseInterval(args);
    Painter painter = new Painter(detectColorMode());
    try (TerminalSession terminal = JLineTerminalSession.open()) {
      new Hugin(reader, terminal, painter, RefreshIntervals.from(interval)).run();
    }
  }

  /**
   * The one flag this verb takes. Bounded at both ends deliberately: below a second the view spends
   * more time polling than an operator can read, and beyond a minute it stops being a live view of
   * anything.
   */
  static Duration parseInterval(final List<String> args) {
    Duration interval = DEFAULT_INTERVAL;
    for (String arg : args) {
      if (!arg.startsWith("--interval=")) {
        throw new CliException(
            "usage: gimle top [--interval=SECS]   (press ? in the view for keys)");
      }
      long seconds;
      try {
        seconds = Long.parseLong(arg.substring("--interval=".length()).trim());
      } catch (NumberFormatException e) {
        throw new CliException("--interval must be a whole number of seconds, got: " + arg, e);
      }
      if (seconds < MIN_INTERVAL_SECONDS || seconds > MAX_INTERVAL_SECONDS) {
        throw new CliException(
            "--interval must be between "
                + MIN_INTERVAL_SECONDS
                + " and "
                + MAX_INTERVAL_SECONDS
                + " seconds, got: "
                + seconds);
      }
      interval = Duration.ofSeconds(seconds);
    }
    return interval;
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
