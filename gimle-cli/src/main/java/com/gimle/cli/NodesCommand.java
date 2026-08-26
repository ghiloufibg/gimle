package com.gimle.cli;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code get nodes}, {@code get node-assignments <nodeId>}, {@code cordon <nodeId>}, {@code
 * uncordon <nodeId>}.
 */
public final class NodesCommand {

  /**
   * Matches the console's own staleness threshold ({@code isStale}'s default in {@code
   * gimle-console/src/lib/format.ts}), so "healthy"/"stale" agrees between the two surfaces.
   */
  private static final long STALE_AFTER_SECONDS = 30;

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public NodesCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void list() {
    List<Map<String, Object>> nodes = client.getList("/nodes");
    // Humanization is table-only: -o json keeps the raw capabilities/capacity fields at full
    // fidelity (exact byte/millicore counts) for scripting, since only the table renderer has
    // nowhere else to put a nested object but one unreadable JSON-blob cell. The one exception is
    // "status": the table's own heartbeat-freshness computation has no server-side counterpart at
    // all, so a JSON consumer would otherwise have no way to reproduce it -- it's added to the raw
    // shape rather than left table-only like everything else here.
    OutputFormat.printList(
        output, output == OutputFormat.Kind.TABLE ? humanize(nodes) : withStatus(nodes), out);
  }

  public void assignments(String nodeId) {
    OutputFormat.printList(output, client.getList("/nodes/" + nodeId + "/assignments"), out);
  }

  public void cordon(String nodeId) {
    client.expectSuccess(client.post("/nodes/" + nodeId + "/cordon", ""));
    out.println("node/" + nodeId + " cordoned");
  }

  public void uncordon(String nodeId) {
    client.expectSuccess(client.post("/nodes/" + nodeId + "/uncordon", ""));
    out.println("node/" + nodeId + " uncordoned");
  }

  /**
   * Flattens each node's own {@code capabilities}/{@code capacity} JSON blobs into
   * table-column-friendly derived fields -- percent-used, human-readable byte/millicore totals, and
   * a heartbeat-freshness status -- the same shape the console computes from this identical data
   * (see {@code gimle-console/src/routes/nodes.index.tsx}). Every row carries the same key set
   * regardless of whether that node has ever heartbeated, since {@link OutputFormat#printTable}
   * derives its column headers from the first row alone; a node with no capacity data yet gets
   * {@code "-"} placeholders rather than a differently-shaped row that would misalign every column
   * after it.
   */
  private static List<Map<String, Object>> humanize(List<Map<String, Object>> nodes) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> node : nodes) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("nodeId", node.get("nodeId"));
      row.put("tiers", String.join(",", supportedTiers(node)));
      row.put("cordoned", node.getOrDefault("cordoned", false));
      row.put("status", statusOf(node.get("lastHeartbeatAt")));
      row.put("lastHeartbeatAt", node.getOrDefault("lastHeartbeatAt", "-"));

      Object rawCapacity = node.get("capacity");
      if (rawCapacity instanceof Map<?, ?> capacity) {
        long totalMemory = longValue(capacity.get("totalMemoryBytes"));
        long assignedMemory = longValue(capacity.get("assignedMemoryBytes"));
        long totalCpu = longValue(capacity.get("totalCpuMillicores"));
        long assignedCpu = longValue(capacity.get("assignedCpuMillicores"));
        row.put("cpuUsedPct", ResourceFormatting.percent(assignedCpu, totalCpu));
        row.put("memUsedPct", ResourceFormatting.percent(assignedMemory, totalMemory));
        row.put("totalCpu", ResourceFormatting.millicores(totalCpu));
        row.put("totalMemory", ResourceFormatting.bytes(totalMemory));
      } else {
        row.put("cpuUsedPct", "-");
        row.put("memUsedPct", "-");
        row.put("totalCpu", "-");
        row.put("totalMemory", "-");
      }
      rows.add(row);
    }
    return rows;
  }

  /**
   * Adds the same {@code status} value {@link #humanize} computes for the table's own column, keyed
   * under the identical field name, to the otherwise-untouched raw node shape -- so a {@code -o
   * json} consumer can read the same heartbeat-freshness verdict without recomputing it, and every
   * other raw field ({@code capabilities}, {@code capacity}, ...) stays exactly as the API returned
   * it.
   */
  private static List<Map<String, Object>> withStatus(List<Map<String, Object>> nodes) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> node : nodes) {
      Map<String, Object> row = new LinkedHashMap<>(node);
      row.put("status", statusOf(node.get("lastHeartbeatAt")));
      rows.add(row);
    }
    return rows;
  }

  private static List<String> supportedTiers(Map<String, Object> node) {
    if (node.get("capabilities") instanceof Map<?, ?> capabilities
        && capabilities.get("supportedTiers") instanceof List<?> tiers) {
      List<String> names = new ArrayList<>();
      for (Object tier : tiers) {
        names.add(String.valueOf(tier));
      }
      return names;
    }
    return List.of();
  }

  private static long longValue(Object value) {
    return value instanceof Number n ? n.longValue() : 0L;
  }

  private static String statusOf(Object lastHeartbeatAt) {
    if (!(lastHeartbeatAt instanceof String iso)) {
      return "UNKNOWN";
    }
    try {
      Instant heartbeat = Instant.parse(iso);
      boolean stale = Duration.between(heartbeat, Instant.now()).getSeconds() > STALE_AFTER_SECONDS;
      return stale ? "STALE" : "HEALTHY";
    } catch (DateTimeParseException e) {
      return "UNKNOWN";
    }
  }
}
