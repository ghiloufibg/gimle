package com.gimle.cli;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code --watch} family of flags a {@code get} invocation may carry, plus whatever arguments
 * were left after they were removed -- {@link GimleCli} strips them before handing the rest to the
 * resource command, which knows nothing about watching.
 *
 * <p>Flags use the {@code --flag=value} spelling {@code gimle logs} already established for {@code
 * --since=}/{@code --level=}, rather than the space-separated spelling {@link Flags} parses, so a
 * watch flag can be recognized and removed without knowing which of the two conventions the
 * resource command behind it happens to use.
 *
 * @param enabled whether {@code --watch}/{@code -w} was given at all
 * @param interval how long to wait between polls
 * @param ticks how many snapshots to print before exiting, or {@code 0} for "until interrupted"
 * @param remainingArgs the arguments with every watch flag removed
 */
record WatchOptions(boolean enabled, Duration interval, int ticks, List<String> remainingArgs) {

  /**
   * Two seconds: fast enough that a rollout, a scale-up or a cordon lands within a tick or two of
   * happening, slow enough that a watch left open against a large cluster stays a negligible share
   * of the control plane's own request budget. {@code --watch-interval=} overrides it.
   */
  static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(2);

  private static final double MIN_INTERVAL_SECONDS = 0.01;
  private static final double MAX_INTERVAL_SECONDS = 3600;

  WatchOptions {
    remainingArgs = List.copyOf(remainingArgs);
  }

  static WatchOptions parse(final List<String> args) {
    boolean enabled = false;
    Duration interval = DEFAULT_INTERVAL;
    int ticks = 0;
    boolean tuned = false;
    final List<String> remaining = new ArrayList<>();
    for (final String arg : args) {
      if (arg.equals("--watch") || arg.equals("-w")) {
        enabled = true;
      } else if (arg.startsWith("--watch-interval=")) {
        interval = parseInterval(arg.substring("--watch-interval=".length()));
        tuned = true;
      } else if (arg.startsWith("--watch-ticks=")) {
        ticks = parseTicks(arg.substring("--watch-ticks=".length()));
        tuned = true;
      } else {
        remaining.add(arg);
      }
    }
    if (tuned && !enabled) {
      throw CliException.invalidInput(
          "--watch-interval/--watch-ticks only mean something alongside --watch");
    }
    return new WatchOptions(enabled, interval, ticks, remaining);
  }

  private static Duration parseInterval(final String raw) {
    final double seconds;
    try {
      seconds = Double.parseDouble(raw);
    } catch (NumberFormatException e) {
      throw CliException.invalidInput("--watch-interval must be a number of seconds, got: " + raw);
    }
    // A zero or negative interval is a busy loop against the control plane, not a faster watch, so
    // it is rejected outright rather than silently clamped to something the operator didn't ask
    // for.
    if (seconds < MIN_INTERVAL_SECONDS || seconds > MAX_INTERVAL_SECONDS) {
      throw CliException.invalidInput(
          "--watch-interval must be between "
              + MIN_INTERVAL_SECONDS
              + " and "
              + MAX_INTERVAL_SECONDS
              + " seconds, got: "
              + raw);
    }
    return Duration.ofNanos(Math.round(seconds * 1_000_000_000d));
  }

  private static int parseTicks(final String raw) {
    final int ticks;
    try {
      ticks = Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw CliException.invalidInput("--watch-ticks must be an integer, got: " + raw);
    }
    if (ticks < 1) {
      throw CliException.invalidInput("--watch-ticks must be at least 1, got: " + raw);
    }
    return ticks;
  }
}
