package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code audit list [--principal <name>] [--resource <kind>] [--tenant <id>] [--since <ts>]
 * [--limit N] [--cursor <token>] [--all]} -- reads the cross-resource audit trail: every
 * access-control decision the control plane records (role/permission changes, secret access,
 * resource writes, and any reads opted into auditing), independent of any single resource kind's
 * own history, so an operator can answer "who did what" without knowing in advance which resource
 * to look under. Every filter is optional and independently combinable, matching {@code
 * ApiServer}'s own {@code GET /audit} query-parameter shape exactly.
 *
 * <p>Without {@code --limit} the trail is returned whole, so paging never gets in the way of a
 * one-shot query. With one, the response reports how many events matched in total and hands back a
 * cursor for the next page -- {@code --cursor} resumes from it, {@code --all} follows it to
 * exhaustion so a filtered trail can be dumped in one command without the caller writing the loop.
 */
public final class AuditCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public AuditCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void run(List<String> args) {
    if (args.isEmpty() || !"list".equals(args.get(0))) {
      throw new CliException(usage());
    }
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of("--all"), usage());

    StringBuilder filters = new StringBuilder();
    appendFilter(filters, "principal", flags.getOrDefault("--principal", null));
    appendFilter(filters, "resource", flags.getOrDefault("--resource", null));
    appendFilter(filters, "tenant", flags.getOrDefault("--tenant", null));
    appendFilter(filters, "since", flags.getOrDefault("--since", null));
    appendFilter(filters, "limit", flags.getOrDefault("--limit", null));
    boolean followEveryPage = flags.isSet("--all");

    List<Map<String, Object>> events = new ArrayList<>();
    String cursor = flags.getOrDefault("--cursor", null);
    Map<String, Object> response;
    boolean expired;
    do {
      response = client.getObject(pathFor(filters.toString(), cursor));
      events.addAll(Json.asObjectList(response.get("events")));
      cursor = (String) response.get("nextCursor");
      expired = Boolean.TRUE.equals(response.get("cursorExpired"));
    } while (followEveryPage && cursor != null);

    OutputFormat.printList(output, events, out);
    if (output == OutputFormat.Kind.JSON) {
      return;
    }
    if (expired) {
      out.println(
          "note: the page this cursor pointed at has already been discarded by the audit trail's"
              + " retention cap; every event older than it is gone too");
    }
    long matchedCount = ((Number) response.get("matchedCount")).longValue();
    if (matchedCount != events.size()) {
      out.printf("note: showing %d of %d matching event(s)%n", events.size(), matchedCount);
    }
    if (cursor != null) {
      out.printf("note: more events match; continue with --cursor %s%n", cursor);
    }
    // Independent of any filter/limit/cursor the query above applied: this describes the whole
    // trail's retention state, not what this call returned.
    if (Boolean.TRUE.equals(response.get("truncated"))) {
      out.printf(
          "note: the audit trail has exceeded its retention cap; %s older event(s) have been"
              + " discarded (retaining %s)%n",
          response.get("evictedTotal"), response.get("retainedCount"));
    }
  }

  private static String pathFor(String filters, String cursor) {
    StringBuilder query = new StringBuilder(filters);
    if (cursor != null) {
      if (!query.isEmpty()) {
        query.append('&');
      }
      query.append("cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
    }
    return query.isEmpty() ? "/audit" : "/audit?" + query;
  }

  private static void appendFilter(StringBuilder filters, String name, String value) {
    if (value == null) {
      return;
    }
    if (!filters.isEmpty()) {
      filters.append('&');
    }
    filters.append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
  }

  static String usage() {
    return """
        usage: gimle audit list [--principal <name>] [--resource <kind>] [--tenant <id>]
                                 [--since <epochMillis>] [--limit N] [--cursor <token>] [--all]""";
  }
}
