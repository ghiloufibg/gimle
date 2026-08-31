package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders a parsed JSON response as either a tab-separated human-readable table (default) or raw
 * JSON ({@code -o json}) -- kubectl's own {@code -o} convention. Column set is derived from the
 * first item's own keys, matching each command's fixed, known-up-front response shape; a nested
 * object/array value (e.g. a tenant's {@code quota}) renders as its own compact JSON rather than a
 * flattened sub-table, since none of this CLI's resource shapes nest deep enough to need one.
 */
public final class OutputFormat {

  public enum Kind {
    TABLE,
    JSON,
    /**
     * Honored only by {@code get <workload-kind> <name>} (Deployment/Job/CronJob/DaemonSet/
     * StatefulSet) via {@link ManifestExport} -- every other command falls through to its own
     * {@code == Kind.JSON} check and renders a table instead, the same as an unrecognized kind
     * would.
     */
    MANIFEST
  }

  private OutputFormat() {}

  public static void printObject(Kind kind, Map<String, Object> object, PrintStream out) {
    if (kind == Kind.JSON) {
      out.println(Json.write(object));
      return;
    }
    printTable(List.of(object), out);
  }

  public static void printList(Kind kind, List<Map<String, Object>> items, PrintStream out) {
    if (kind == Kind.JSON) {
      out.println(Json.write(items));
      return;
    }
    printTable(items, out);
  }

  /**
   * For a write verb's one-line acknowledgement (set/delete/apply), which has no resource body to
   * render as a table -- {@code jsonBody} under {@code -o json}, the existing human sentence
   * unchanged under the default table format. Read verbs go through {@link #printObject}/{@link
   * #printList} instead, which already honor {@code Kind} correctly.
   */
  public static void printResult(
      Kind kind, Map<String, Object> jsonBody, String tableMessage, PrintStream out) {
    if (kind == Kind.JSON) {
      out.println(Json.write(jsonBody));
      return;
    }
    out.println(tableMessage);
  }

  private static void printTable(List<Map<String, Object>> items, PrintStream out) {
    if (items.isEmpty()) {
      out.println("No resources found.");
      return;
    }
    List<String> columns = new ArrayList<>(items.get(0).keySet());
    out.println(String.join("\t", columns));
    for (Map<String, Object> item : items) {
      List<String> row = new ArrayList<>();
      for (String column : columns) {
        row.add(formatValue(item.get(column)));
      }
      out.println(String.join("\t", row));
    }
  }

  private static String formatValue(Object value) {
    if (value == null) {
      return "-";
    }
    if (value instanceof Map || value instanceof List) {
      return Json.write(value);
    }
    return String.valueOf(value);
  }
}
