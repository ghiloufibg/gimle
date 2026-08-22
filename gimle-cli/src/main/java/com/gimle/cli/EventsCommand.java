package com.gimle.cli;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code events <deploymentName> <instanceIndex> [--limit N]} -- an instance's own lifecycle
 * timeline. {@code GET /events} always returns the full timeline, newest-first (see {@code
 * ApiServer#handleEvents}'s own javadoc); it carries no {@code limit} query parameter the way
 * {@code GET /audit} does, so {@code --limit} is applied client-side by truncating the
 * already-newest-first response rather than by adding a query-string parameter the server would
 * silently ignore.
 */
public final class EventsCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public EventsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void run(String deploymentName, String instanceIndex, List<String> extraArgs) {
    Flags flags = Flags.parse(extraArgs, Set.of());
    String path = "/events?deployment=" + deploymentName + "&instance=" + instanceIndex;
    List<Map<String, Object>> events = client.getList(path);
    String limitValue = flags.getOrDefault("--limit", null);
    if (limitValue != null) {
      int limit = parseLimit(limitValue);
      if (events.size() > limit) {
        events = events.subList(0, limit);
      }
    }
    OutputFormat.printList(output, events, out);
  }

  private static int parseLimit(String value) {
    int limit;
    try {
      limit = Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new CliException("--limit must be a number: " + value);
    }
    if (limit < 0) {
      throw new CliException("--limit must not be negative: " + value);
    }
    return limit;
  }
}
