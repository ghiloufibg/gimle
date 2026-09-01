package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code metrics-history}/{@code traces-history} -- a process's own shipped observability history,
 * read back through the control plane's {@code GET /metrics-history/{processKind}/{processId}} and
 * {@code GET /traces-history/{processKind}/{processId}} proxies onto Muninn. Both surfaces have the
 * identical request shape and response envelope ({@code lines} plus paging cursors), so one command
 * serves both, picking the path prefix off {@link Surface}.
 *
 * <p>There is no discovery API for which process ids exist: every non-agent id is a {@code
 * host:port} that process chose for itself at startup, and a worker's is the composite {@code
 * {nodeId}:{workerId}} (a worker has no listening address of its own). The process kind is checked
 * against the known set here so a typo reports the five legal kinds instead of silently reading an
 * empty history; the id itself can only be checked for shape.
 *
 * <p>{@code --since <cursor>} is the one filter the control-plane proxy forwards. {@code --limit N}
 * is therefore applied here, to the tail of an oldest-first response, rather than as a query
 * parameter the proxy would drop on the floor -- the same client-side treatment {@link
 * EventsCommand} gives its own {@code --limit} for the same reason.
 */
public final class HistoryCommand {

  /** Which of the two history surfaces an invocation reads. */
  public enum Surface {
    METRICS("metrics-history"),
    TRACES("traces-history");

    private final String verb;

    Surface(String verb) {
      this.verb = verb;
    }

    String verb() {
      return verb;
    }
  }

  private static final Set<String> PROCESS_KINDS =
      Set.of("CONTROLPLANE", "FAFNIR", "STORE", "AGENT", "WORKER");

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public HistoryCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void run(Surface surface, List<String> args) {
    if (args.size() < 2) {
      throw new CliException(usage(surface));
    }
    String processKind = requireProcessKind(surface, args.get(0));
    String processId = requireProcessId(surface, processKind, args.get(1));
    Flags flags = Flags.parse(args.subList(2, args.size()), Set.of(), usage(surface));

    StringBuilder path =
        new StringBuilder("/").append(surface.verb()).append('/').append(processKind).append('/');
    path.append(URLEncoder.encode(processId, StandardCharsets.UTF_8));
    String since = flags.getOrDefault("--since", null);
    if (since != null && !since.isBlank()) {
      path.append("?since=").append(URLEncoder.encode(since, StandardCharsets.UTF_8));
    }

    Map<String, Object> envelope = client.getObject(path.toString());
    List<Map<String, Object>> lines = Json.asObjectList(envelope.get("lines"));
    String limitValue = flags.getOrDefault("--limit", null);
    if (limitValue != null) {
      int limit = parseLimit(limitValue);
      if (lines.size() > limit) {
        lines = lines.subList(lines.size() - limit, lines.size());
      }
    }
    OutputFormat.printList(output, lines, out);
    printResumeHint(lines);
  }

  /**
   * The cursor to resume from is the last line actually printed, not the envelope's own {@code
   * newerCursor}: with {@code --limit} applied client-side those two disagree, and resuming from
   * the envelope's cursor would skip everything the truncation dropped. Table format only -- {@code
   * -o json} output stays parseable as a single JSON document.
   */
  private void printResumeHint(List<Map<String, Object>> lines) {
    if (output != OutputFormat.Kind.TABLE || lines.isEmpty()) {
      return;
    }
    Object timestamp = lines.get(lines.size() - 1).get("timestamp");
    if (timestamp != null) {
      out.printf("note: resume with --since %s%n", timestamp);
    }
  }

  private static String requireProcessKind(Surface surface, String value) {
    String upper = value.toUpperCase(Locale.ROOT);
    if (!PROCESS_KINDS.contains(upper)) {
      throw new CliException(
          "unknown process kind: "
              + value
              + " (expected one of CONTROLPLANE, FAFNIR, STORE, AGENT, WORKER)\n\n"
              + usage(surface));
    }
    return upper;
  }

  private static String requireProcessId(Surface surface, String processKind, String value) {
    if (value.isBlank() || value.contains("/")) {
      throw new CliException("invalid processId: " + value + "\n\n" + usage(surface));
    }
    if ("WORKER".equals(processKind)) {
      int colon = value.indexOf(':');
      if (colon <= 0 || colon == value.length() - 1) {
        throw new CliException(
            "a WORKER processId is {nodeId}:{workerId} -- a worker has no address of its own,"
                + " so its history is filed under its node's id and its own worker id, got: "
                + value);
      }
    }
    return value;
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

  static String usage(Surface surface) {
    return "usage: gimle "
        + surface.verb()
        + " <CONTROLPLANE|FAFNIR|STORE|AGENT|WORKER> <processId> [--since <cursor>] [--limit N]";
  }
}
