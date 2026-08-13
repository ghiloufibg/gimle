package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * A real {@code kubectl logs} equivalent: {@code gimle logs <target> [--category=...] [--follow]
 * [--since=<cursor>]}, sharing the identical backend routes and JSON shapes {@code
 * src/repositories/http/logs.ts} uses. Without {@code --follow}, one request, print, exit. With
 * {@code --follow}, opens the same chunked stream the console's {@code openFollow} equivalent reads
 * from and prints lines as they arrive until the process is interrupted.
 */
public final class LogsCommand {

  private final ControlPlaneClient client;
  private final PrintStream out;

  public LogsCommand(ControlPlaneClient client, PrintStream out) {
    this.client = client;
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

    for (String arg : args.subList(1, args.size())) {
      if (arg.equals("--follow") || arg.equals("-f")) {
        follow = true;
      } else if (arg.startsWith("--category=")) {
        category = arg.substring("--category=".length());
      } else if (arg.startsWith("--since=")) {
        since = arg.substring("--since=".length());
      } else {
        throw new CliException("unknown flag: " + arg + "\n\n" + usage());
      }
    }

    if (follow) {
      runFollow(path, category, since);
    } else {
      runOnce(path, category, since);
    }
  }

  private void runOnce(String path, String category, String since) {
    // "since" here means "everything after this point" (LogFileReader.readAfter), a different
    // operation from the plain GET route's own "cursor" param ("page backward from here",
    // readOlder) -- passing --since as cursor would silently invert the result to "older than",
    // the same bug the console's openFollow hit and fixed the same way (see logs.ts).
    StringBuilder query = new StringBuilder("?category=").append(category).append("&limit=200");
    if (since != null) {
      query.append("&since=").append(since);
    }
    Map<String, Object> body = client.getObject(path + query);
    for (Object rawLine : Json.asArray(body.get("lines"))) {
      out.println(formatLine(Json.asObject(rawLine)));
    }
  }

  private void runFollow(String path, String category, String since) {
    StringBuilder query = new StringBuilder("?category=").append(category).append("&follow=true");
    if (since != null) {
      query.append("&cursor=").append(since);
    }
    try (InputStream body = client.openStream(path + query);
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        out.println(formatLine(Json.asObject(Json.parse(line))));
      }
    } catch (IOException e) {
      throw new CliException("log stream ended: " + e.getMessage(), e);
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

  private static String usage() {
    return """
        usage: gimle logs <target> [--category=CAT] [--follow|-f] [--since=<cursor>]
          target: controlplane | node/<nodeId> | instance/<deploymentName>/<instanceIndex>
          --category: APPLICATION|PLATFORM for instances, PLATFORM|SYSTEM for nodes/controlplane
                      (defaults to APPLICATION for instances, PLATFORM otherwise)
          --follow, -f: stream new lines as they arrive, like kubectl logs -f
          --since: resume from an opaque cursor returned by a previous call""";
  }
}
