package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code get daemonsets [name]}, {@code apply -f <file.yaml>}, {@code delete daemonset <name>} --
 * mirrors {@link DeploymentsCommand}/{@link JobsCommand} exactly, including the same table-vs-json
 * split: {@code -o table} (the default) flattens each status's nested {@code spec}/{@code
 * instances} into clean summary columns ({@link #humanize}), while {@code -o json} keeps the raw
 * shape -- including each instance's assigned {@code nodeId} -- at full fidelity. Deliberately no
 * {@code scale}-equivalent verb: a DaemonSet's replica count isn't operator-settable the way a
 * Deployment's is, it's topology-derived (one per eligible node) -- there is nothing here for a
 * scale verb to set, and no "desired" count for the table's own replica column to compare against
 * either, unlike a Deployment's or StatefulSet's. {@code apply} itself is dispatched here by {@link
 * GimleCli} once it has peeked at the manifest's own {@code kind:} field, the same way every other
 * kind's is.
 */
public final class DaemonSetsCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public DaemonSetsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    if (args.isEmpty()) {
      List<Map<String, Object>> daemonSets = client.getList("/daemonsets");
      List<Map<String, Object>> rows =
          output == OutputFormat.Kind.TABLE ? humanizeAll(daemonSets) : daemonSets;
      OutputFormat.printList(output, rows, out);
      return;
    }
    String name = args.get(0);
    Map<String, Object> daemonSet = client.getObject("/daemonsets/" + name);
    OutputFormat.printObject(
        output, output == OutputFormat.Kind.TABLE ? humanize(daemonSet) : daemonSet, out);
  }

  public void apply(List<String> args, PrintStream err) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    String name = ManifestFiles.extractName(file, manifestBytes);
    ApiResponse response =
        client.put("/daemonsets/" + name, new String(manifestBytes, StandardCharsets.UTF_8));
    client.expectSuccess(response);
    ManifestFiles.printWarnings(response, err);
    OutputFormat.printResult(
        output, resultBody("applied", name), "daemonset/" + name + " applied", out);
  }

  public void delete(String name) {
    client.expectSuccess(client.delete("/daemonsets/" + name));
    OutputFormat.printResult(
        output, resultBody("deleted", name), "daemonset/" + name + " deleted", out);
  }

  /** {@code daemonset revisions <name>} -- mirrors {@link DeploymentsCommand#revisions}. */
  public void revisions(String name) {
    Map<String, Object> response = client.getObject("/daemonsets/" + name + "/revisions");
    OutputFormat.printList(output, Json.asObjectList(response.get("revisions")), out);
  }

  /**
   * {@code daemonset rollback <name> [--to-revision N]} -- mirrors {@link
   * DeploymentsCommand#rollback}.
   */
  public void rollback(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("daemonset rollback requires <name> [--to-revision N]");
    }
    String name = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of());
    Map<String, Object> body = new LinkedHashMap<>();
    String toRevision = flags.getOrDefault("--to-revision", null);
    if (toRevision != null) {
      body.put("toRevision", parseRevision(toRevision));
    }
    String response =
        client.expectSuccess(client.post("/daemonsets/" + name + "/rollback", Json.write(body)));
    OutputFormat.printObject(output, Json.asObject(Json.parse(response)), out);
  }

  private static int parseRevision(String raw) {
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw new CliException("--to-revision must be an integer, got: " + raw);
    }
  }

  private static Map<String, Object> resultBody(String result, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "daemonset");
    body.put("id", name);
    return body;
  }

  private static List<Map<String, Object>> humanizeAll(List<Map<String, Object>> daemonSets) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> daemonSet : daemonSets) {
      rows.add(humanize(daemonSet));
    }
    return rows;
  }

  /**
   * Flattens one daemonset status's nested {@code spec}/{@code instances} fields into
   * table-column-friendly derived fields, mirroring {@code DeploymentsCommand#humanize} -- module
   * coordinate and a rollup health status. No "placed/desired" replicas column: a DaemonSet's
   * status carries no target count to compare {@code instances.size()} against (see the class
   * javadoc), so {@code instances} is just the current count.
   */
  private static Map<String, Object> humanize(Map<String, Object> status) {
    Map<?, ?> spec = status.get("spec") instanceof Map<?, ?> m ? m : Map.of();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", spec.get("name"));
    row.put("module", moduleCoordinate(spec.get("moduleId")));
    row.put("artifactPath", orDash(spec.get("artifactPath")));
    row.put("tenantId", orDash(spec.get("tenantId")));
    Object rawInstances = status.get("instances");
    int placedInstances = rawInstances instanceof List<?> instances ? instances.size() : 0;
    row.put("instances", placedInstances);
    row.put("health", healthOf(rawInstances));
    return row;
  }

  private static String moduleCoordinate(Object rawModuleId) {
    if (rawModuleId instanceof Map<?, ?> moduleId) {
      return moduleId.get("name") + "@" + moduleId.get("version");
    }
    return "-";
  }

  private static String healthOf(Object instances) {
    int unhealthyCount = unhealthyInstanceCount(instances);
    return unhealthyCount > 0 ? "UNHEALTHY(" + unhealthyCount + ")" : "HEALTHY";
  }

  /** Same definition {@code DeploymentsCommand#unhealthyInstanceCount} uses -- see its javadoc. */
  private static int unhealthyInstanceCount(Object instances) {
    if (!(instances instanceof List<?> list)) {
      return 0;
    }
    int count = 0;
    for (Object entry : list) {
      if (!(entry instanceof Map<?, ?> instance)) {
        continue;
      }
      if (instance.get("observation") instanceof Map<?, ?> observation
          && (Boolean.FALSE.equals(observation.get("alive"))
              || "FAILED".equals(observation.get("lifecycleState")))) {
        count++;
      }
    }
    return count;
  }

  private static Object orDash(Object value) {
    return value == null ? "-" : value;
  }
}
