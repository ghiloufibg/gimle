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
 * against the kinds that really do ship the signal being read, so a typo -- or a kind that ships
 * the other signal only -- reports the legal kinds instead of silently reading an empty history;
 * the id itself can only be checked for shape. The two surfaces accept different kinds because the
 * platform ships different signals from different processes: most process kinds publish metrics but
 * never start a span (a node agent and Skald install no tracer provider at all; the store, Fafnir
 * and Andvari install one and produce nothing for it), so asking any of them for traces could only
 * ever come back empty. The control plane serves the same two sets from {@code GET
 * /metrics-history} and {@code GET /traces-history}.
 *
 * <p>{@code --since <cursor>} and {@code --limit N} both travel to the store as query parameters,
 * so a limit bounds what is read rather than trimming what came back.
 */
public final class HistoryCommand {

  /** Which of the two history surfaces an invocation reads, and which kinds ship to it. */
  public enum Surface {
    METRICS(
        "metrics-history",
        List.of("AGENT", "ANDVARI", "CONTROLPLANE", "FAFNIR", "SKALD", "STORE", "WORKER")),
    TRACES("traces-history", List.of("CONTROLPLANE", "WORKER"));

    private final String verb;
    private final List<String> processKinds;

    Surface(String verb, List<String> processKinds) {
      this.verb = verb;
      this.processKinds = processKinds;
    }

    String verb() {
      return verb;
    }

    /** The process kinds whose own data is shipped to this surface, alphabetically. */
    public List<String> processKinds() {
      return processKinds;
    }
  }

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

    String limitValue = flags.getOrDefault("--limit", null);
    if (limitValue != null) {
      path.append(path.indexOf("?") < 0 ? "?" : "&")
          .append("limit=")
          .append(parseLimit(limitValue));
    }

    Map<String, Object> envelope = client.getObject(path.toString());
    List<Map<String, Object>> lines = Json.asObjectList(envelope.get("lines"));
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
    if (!surface.processKinds().contains(upper)) {
      throw new CliException(
          "no "
              + surface.name().toLowerCase(Locale.ROOT)
              + " are shipped for process kind: "
              + value
              + " (expected one of "
              + String.join(", ", surface.processKinds())
              + ")\n\n"
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

  /**
   * {@code gimle trace <traceId>} -- every span of one trace, wherever it ran. The per-process
   * reads above cannot answer this: a caller would have to already know which processes took part,
   * and a worker replaced since the call no longer appears in any live listing to be named.
   */
  void runTraceSearch(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException(traceUsage());
    }
    String traceId = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of(), traceUsage());
    StringBuilder path =
        new StringBuilder("/trace/").append(URLEncoder.encode(traceId, StandardCharsets.UTF_8));
    String limitValue = flags.getOrDefault("--limit", null);
    if (limitValue != null) {
      path.append("?limit=").append(parseLimit(limitValue));
    }
    Map<String, Object> found = client.getObject(path.toString());
    OutputFormat.printList(output, Json.asObjectList(found.get("spans")), out);
    if (Boolean.TRUE.equals(found.get("truncated"))) {
      out.println("(truncated at the limit; raise --limit to read the rest)");
    }
  }

  static String traceUsage() {
    return "usage: gimle trace <traceId> [--limit N]";
  }

  static String usage(Surface surface) {
    return "usage: gimle "
        + surface.verb()
        + " <"
        + String.join("|", surface.processKinds())
        + "> <processId> [--since <cursor>] [--limit N]";
  }
}
