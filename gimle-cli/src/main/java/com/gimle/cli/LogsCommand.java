package com.gimle.cli;

import com.gimle.core.logging.LogFilter;
import com.gimle.core.protocol.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * A real {@code kubectl logs} equivalent: {@code gimle logs <target> [--category=...] [--follow]
 * [--since=<cursor>] [--level=<LEVEL>] [--contains=<text>]}, sharing the identical backend routes
 * and JSON shapes {@code src/repositories/http/logs.ts} uses. Without {@code --follow}, one
 * request, print, exit. With {@code --follow}, opens the same chunked stream the console's {@code
 * openFollow} equivalent reads from and prints lines as they arrive until the process is
 * interrupted.
 *
 * <p>{@code --level}/{@code --contains} are applied by whichever log reader answers, never here: an
 * operator hunting one ERROR line in a high-volume log gets only the matching lines over the wire,
 * not the whole stream to grep locally.
 *
 * <p>Under {@code -o json} the structured line objects the reader already sends are emitted as they
 * are, never a re-serialization of the human one-line rendering: a JSON consumer gets the
 * timestamp, level, logger, message, and stack trace as separate fields. A single request prints
 * one JSON array (an empty one when nothing matched, so a zero-match query is still valid JSON to
 * pipe onward); {@code --follow} prints one JSON object per line as it arrives, since a stream that
 * never ends has no closing bracket to print.
 */
public final class LogsCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public LogsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void run(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException(usage());
    }
    String target = args.get(0);
    String path = resolvePath(target);
    String category = defaultCategoryFor(target);
    boolean follow = false;
    String since = null;
    String level = null;
    String contains = null;

    for (String arg : args.subList(1, args.size())) {
      if (arg.equals("--follow") || arg.equals("-f")) {
        follow = true;
      } else if (arg.startsWith("--category=")) {
        category = arg.substring("--category=".length());
      } else if (arg.startsWith("--since=")) {
        since = arg.substring("--since=".length());
      } else if (arg.startsWith("--level=")) {
        level = arg.substring("--level=".length());
      } else if (arg.startsWith("--contains=")) {
        contains = arg.substring("--contains=".length());
      } else {
        throw new CliException("unknown flag: " + arg + "\n\n" + usage());
      }
    }

    // Parsed locally as well as server-side so a typo'd level fails immediately with the accepted
    // values, rather than after a round trip -- and, under --follow, rather than leaving the
    // operator staring at a stream that will never produce a line.
    final LogFilter filter;
    try {
      filter = LogFilter.of(level, contains);
    } catch (IllegalArgumentException e) {
      throw new CliException(e.getMessage(), e);
    }

    if (follow) {
      runFollow(path, category, since, filter);
    } else {
      runOnce(path, category, since, filter);
    }
  }

  private void runOnce(String path, String category, String since, LogFilter filter) {
    // "since" here means "everything after this point" (LogFileReader.readAfter), a different
    // operation from the plain GET route's own "cursor" param ("page backward from here",
    // readOlder) -- passing --since as cursor would silently invert the result to "older than",
    // the same bug the console's openFollow hit and fixed the same way (see logs.ts).
    StringBuilder query = new StringBuilder("?category=").append(category).append("&limit=200");
    if (since != null) {
      query.append("&since=").append(since);
    }
    appendFilter(query, filter);
    Map<String, Object> body = client.getObject(path + query);
    List<Map<String, Object>> lines = Json.asObjectList(body.get("lines"));
    if (output == OutputFormat.Kind.JSON) {
      OutputFormat.printList(output, lines, out);
      return;
    }
    for (Map<String, Object> line : lines) {
      out.println(formatLine(line));
    }
    if (lines.isEmpty()) {
      // Silence would be indistinguishable from a broken query -- say so, and say what was
      // filtered on, so an operator can tell "nothing matched" from "nothing was logged".
      out.println(
          filter.isEmpty() ? "(no log lines)" : "(no log lines matched " + filter.describe() + ")");
    }
  }

  private void runFollow(String path, String category, String since, LogFilter filter) {
    StringBuilder query = new StringBuilder("?category=").append(category).append("&follow=true");
    if (since != null) {
      query.append("&cursor=").append(since);
    }
    appendFilter(query, filter);
    try (InputStream body = client.openStream(path + query);
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        Map<String, Object> parsed = Json.asObject(Json.parse(line));
        out.println(output == OutputFormat.Kind.JSON ? Json.write(parsed) : formatLine(parsed));
      }
    } catch (IOException e) {
      throw new CliException("log stream ended: " + e.getMessage(), e);
    }
  }

  /**
   * {@code contains} is arbitrary operator-supplied text -- percent-encoded so a message fragment
   * carrying {@code &}, {@code =} or a space can't corrupt the rest of the query string.
   */
  private static void appendFilter(StringBuilder query, LogFilter filter) {
    if (filter.minLevel() != null) {
      query.append("&level=").append(filter.minLevel());
    }
    if (filter.contains() != null) {
      query
          .append("&contains=")
          .append(URLEncoder.encode(filter.contains(), StandardCharsets.UTF_8));
    }
  }

  private static String formatLine(Map<String, Object> line) {
    if (line.containsKey("raw")) {
      return line.get("timestamp") + " [SYSTEM] " + line.get("raw");
    }
    return line.get("timestamp")
        + " "
        + line.get("level")
        + " "
        + line.get("logger")
        + " - "
        + line.get("message");
  }

  private static String resolvePath(String target) {
    if (target.equals("controlplane")) {
      return "/logs/controlplane";
    }
    if (target.startsWith("node/")) {
      String nodeId = target.substring("node/".length());
      if (nodeId.isBlank()) {
        throw new CliException("expected node/<nodeId>, got: " + target);
      }
      return "/logs/nodes/" + nodeId;
    }
    if (target.startsWith("instance/")) {
      String rest = target.substring("instance/".length());
      int slash = rest.lastIndexOf('/');
      if (slash < 0) {
        throw new CliException(
            "expected instance/<deploymentName>/<instanceIndex>, got: " + target);
      }
      return "/logs/instances/" + rest.substring(0, slash) + "/" + rest.substring(slash + 1);
    }
    throw new CliException(
        "unknown log target: "
            + target
            + " (expected controlplane, node/<id>, or instance/<name>/<idx>)");
  }

  private static String defaultCategoryFor(String target) {
    return target.startsWith("instance/") ? "APPLICATION" : "PLATFORM";
  }

  static String usage() {
    return """
        usage: gimle logs <target> [--category=CAT] [--follow|-f] [--since=<cursor>]
                                   [--level=LEVEL] [--contains=TEXT]
          target: controlplane | node/<nodeId> | instance/<deploymentName>/<instanceIndex>
          --category: APPLICATION|PLATFORM for instances, PLATFORM|SYSTEM for nodes/controlplane
                      (defaults to APPLICATION for instances, PLATFORM otherwise)
          --follow, -f: stream new lines as they arrive, like kubectl logs -f
          --since: resume from an opaque cursor returned by a previous call
          --level: TRACE|DEBUG|INFO|WARN|ERROR -- a threshold, so --level=WARN keeps WARN and
                   ERROR; a line with no level (raw SYSTEM capture) is never kept by one
          --contains: keep only lines whose message, logger, stack trace or raw text contains
                      this text, case-insensitively (plain substring, not a regex)
          -o json: emit the structured log lines themselves -- one JSON array per request, or one
                   JSON object per line under --follow""";
  }
}
