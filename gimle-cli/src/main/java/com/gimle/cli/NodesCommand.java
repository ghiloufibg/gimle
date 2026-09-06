package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code get nodes}, {@code get node-assignments <nodeId>}, {@code cordon <nodeId>}, {@code
 * uncordon <nodeId>}, {@code taint <nodeId> <tenantId>}, {@code untaint <nodeId> <tenantId>}.
 */
public final class NodesCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public NodesCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  private static final String GET_USAGE = "usage: gimle get nodes";

  /**
   * {@code get nodes} takes no name and no flags of its own -- any {@code "--"}-prefixed argument
   * is an unrecognized flag, rejected rather than silently dropped the way an unparsed argument
   * here previously was.
   */
  public void list(List<String> args) {
    GetCommandArgs.splitNoName(args, Set.of(), GET_USAGE);
    OutputFormat.printList(output, listRows(), out);
  }

  /**
   * One snapshot's worth of node rows, rendered exactly as {@link #list} renders them -- what
   * {@code --watch} re-fetches each tick and diffs against the previous one.
   *
   * <p>Humanization is table-only: -o json keeps the raw capabilities/capacity fields at full
   * fidelity (exact byte/millicore counts) for scripting, since only the table renderer has nowhere
   * else to put a nested object but one unreadable JSON-blob cell. The one exception is {@code
   * status}: the table's own heartbeat-freshness computation has no server-side counterpart at all,
   * so a JSON consumer would otherwise have no way to reproduce it -- it's added to the raw shape
   * rather than left table-only like everything else here.
   */
  public List<Map<String, Object>> listRows() {
    List<Map<String, Object>> nodes = client.getList("/nodes");
    return output == OutputFormat.Kind.TABLE ? humanize(nodes) : withStatus(nodes);
  }

  public void assignments(String nodeId) {
    OutputFormat.printList(output, assignmentRows(nodeId), out);
  }

  /** {@link #assignments}' own rows, for the same per-tick re-fetch {@link #listRows} serves. */
  public List<Map<String, Object>> assignmentRows(String nodeId) {
    return client.getList("/nodes/" + nodeId + "/assignments");
  }

  public void cordon(String nodeId) {
    client.expectSuccess(client.post("/nodes/" + nodeId + "/cordon", ""));
    OutputFormat.printResult(
        output, resultBody("cordoned", nodeId), "node/" + nodeId + " cordoned", out);
  }

  public void uncordon(String nodeId) {
    client.expectSuccess(client.post("/nodes/" + nodeId + "/uncordon", ""));
    OutputFormat.printResult(
        output, resultBody("uncordoned", nodeId), "node/" + nodeId + " uncordoned", out);
  }

  public void taint(String nodeId, String tenantId) {
    client.expectSuccess(
        client.post("/nodes/" + nodeId + "/taint", Json.write(Map.of("tenantId", tenantId))));
    Map<String, Object> body = resultBody("tainted", nodeId);
    body.put("tenantId", tenantId);
    OutputFormat.printResult(
        output, body, "node/" + nodeId + " tainted for tenant " + tenantId, out);
  }

  public void untaint(String nodeId, String tenantId) {
    client.expectSuccess(
        client.post("/nodes/" + nodeId + "/untaint", Json.write(Map.of("tenantId", tenantId))));
    Map<String, Object> body = resultBody("untainted", nodeId);
    body.put("tenantId", tenantId);
    OutputFormat.printResult(
        output, body, "node/" + nodeId + " untainted for tenant " + tenantId, out);
  }

  /**
   * Applies {@code additions} and removes {@code removals} from this node's operator-applied
   * labels. The API itself is declarative (it takes the full set), so the current set is read first
   * and the edits folded into it -- the CLI's own surface stays edit-shaped, matching how an
   * operator thinks about labelling one node.
   */
  public void label(String nodeId, Set<String> additions, Set<String> removals) {
    Map<String, Object> node = client.getObject("/nodes/" + nodeId);
    Set<String> labels = new LinkedHashSet<>(operatorLabels(node));
    labels.addAll(additions);
    labels.removeAll(removals);
    client.expectSuccess(
        client.put(
            "/nodes/" + nodeId + "/labels", Json.write(Map.of("labels", List.copyOf(labels)))));
    Map<String, Object> body = resultBody("labelled", nodeId);
    body.put("labels", List.copyOf(labels));
    OutputFormat.printResult(
        output,
        body,
        "node/" + nodeId + " labelled " + (labels.isEmpty() ? "(none)" : String.join(",", labels)),
        out);
  }

  private static List<String> operatorLabels(Map<String, Object> node) {
    if (!(node.get("capabilities") instanceof Map<?, ?> capabilities)) {
      return List.of();
    }
    if (!(capabilities.get("operatorLabels") instanceof List<?> labels)) {
      return List.of();
    }
    return labels.stream().map(String::valueOf).toList();
  }

  private static Map<String, Object> resultBody(String result, String nodeId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "node");
    body.put("id", nodeId);
    return body;
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
      row.put("taints", String.join(",", taints(node)));
      row.put("status", statusOf(node.get("status")));
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
      row.put("status", statusOf(node.get("status")));
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

  private static List<String> taints(Map<String, Object> node) {
    if (node.get("taints") instanceof List<?> taints) {
      List<String> names = new ArrayList<>();
      for (Object taint : taints) {
        names.add(String.valueOf(taint));
      }
      return names;
    }
    return List.of();
  }

  private static long longValue(Object value) {
    return value instanceof Number n ? n.longValue() : 0L;
  }

  /**
   * Reported by the control plane, not derived here from the heartbeat timestamp. Only the control
   * plane knows how long the store has actually been in a position to hear a heartbeat, so only it
   * can tell a node that has gone quiet from one whose heartbeat was cleared by a store election a
   * moment ago -- and deriving it here also meant carrying a second copy of the staleness threshold
   * that could disagree with the platform's own.
   */
  private static String statusOf(Object status) {
    return status instanceof String s && !s.isBlank() ? s : "UNKNOWN";
  }
}
