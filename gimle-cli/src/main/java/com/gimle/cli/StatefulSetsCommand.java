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
 * {@code get statefulsets [name]}, {@code apply -f <file.yaml>}, {@code delete statefulset <name>}
 * -- mirrors {@link DeploymentsCommand} exactly, including having no dedicated {@code scale} verb:
 * like a Deployment, {@code replicas} is changed by resubmitting the manifest via {@code apply},
 * not a separate command, and including the same table-vs-json split: {@code -o table} (the
 * default) flattens each status's nested {@code spec}/{@code instances} into clean summary columns
 * ({@link #humanize}), while {@code -o json} keeps the raw shape -- including each index's assigned
 * {@code nodeId}, the sticky-placement contract's own visibility -- at full fidelity.
 */
public final class StatefulSetsCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public StatefulSetsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    if (args.isEmpty()) {
      List<Map<String, Object>> statefulSets = client.getList("/statefulsets");
      List<Map<String, Object>> rows =
          output == OutputFormat.Kind.TABLE ? humanizeAll(statefulSets) : statefulSets;
      OutputFormat.printList(output, rows, out);
      return;
    }
    String name = args.get(0);
    Map<String, Object> statefulSet = client.getObject("/statefulsets/" + name);
    OutputFormat.printObject(
        output, output == OutputFormat.Kind.TABLE ? humanize(statefulSet) : statefulSet, out);
  }

  public void apply(List<String> args, PrintStream err) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    String name = ManifestFiles.extractName(file, manifestBytes);
    ApiResponse response =
        client.put("/statefulsets/" + name, new String(manifestBytes, StandardCharsets.UTF_8));
    client.expectSuccess(response);
    ManifestFiles.printWarnings(response, err);
    OutputFormat.printResult(
        output, resultBody("applied", name), "statefulset/" + name + " applied", out);
  }

  public void delete(String name) {
    client.expectSuccess(client.delete("/statefulsets/" + name));
    OutputFormat.printResult(
        output, resultBody("deleted", name), "statefulset/" + name + " deleted", out);
  }

  /** {@code statefulset revisions <name>} -- mirrors {@link DeploymentsCommand#revisions}. */
  public void revisions(String name) {
    Map<String, Object> response = client.getObject("/statefulsets/" + name + "/revisions");
    OutputFormat.printList(output, Json.asObjectList(response.get("revisions")), out);
  }

  /**
   * {@code statefulset rollback <name> [--to-revision N]} -- mirrors {@link
   * DeploymentsCommand#rollback}.
   */
  public void rollback(List<String> args) {
    String usage = "statefulset rollback requires <name> [--to-revision N]";
    if (args.isEmpty()) {
      throw new CliException(usage);
    }
    String name = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of(), usage);
    Map<String, Object> body = new LinkedHashMap<>();
    String toRevision = flags.getOrDefault("--to-revision", null);
    if (toRevision != null) {
      body.put("toRevision", parseRevision(toRevision));
    }
    String response =
        client.expectSuccess(client.post("/statefulsets/" + name + "/rollback", Json.write(body)));
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
    body.put("kind", "statefulset");
    body.put("id", name);
    return body;
  }

  private static List<Map<String, Object>> humanizeAll(List<Map<String, Object>> statefulSets) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> statefulSet : statefulSets) {
      rows.add(humanize(statefulSet));
    }
    return rows;
  }

  /**
   * Flattens one statefulset status's nested {@code spec}/{@code instances} fields into
   * table-column-friendly derived fields, mirroring {@code DeploymentsCommand#humanize} -- module
   * coordinate, placed-vs-desired replica count, and a rollup health status. Unlike a Deployment's
   * status, a StatefulSet's never carries {@code quotaViolating}/{@code limitRangeViolating} (see
   * {@code ApiServer#handlePutStatefulSet}'s own "No tenant-quota check here" comment), so this
   * health rollup only ever reports unplaced/unhealthy, never quota or limit-range issues.
   */
  private static Map<String, Object> humanize(Map<String, Object> status) {
    Map<?, ?> spec = status.get("spec") instanceof Map<?, ?> m ? m : Map.of();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", spec.get("name"));
    row.put("module", moduleCoordinate(spec.get("moduleId")));
    row.put("artifactPath", orDash(spec.get("artifactPath")));
    row.put("tenantId", orDash(spec.get("tenantId")));
    int desiredReplicas = intValue(spec.get("replicas"));
    int placedInstances =
        status.get("instances") instanceof List<?> instances ? instances.size() : 0;
    row.put("replicas", placedInstances + "/" + desiredReplicas);
    row.put("health", healthOf(intValue(status.get("unplacedCount")), status.get("instances")));
    return row;
  }

  private static String moduleCoordinate(Object rawModuleId) {
    if (rawModuleId instanceof Map<?, ?> moduleId) {
      return moduleId.get("name") + "@" + moduleId.get("version");
    }
    return "-";
  }

  private static String healthOf(int unplacedCount, Object instances) {
    List<String> issues = new ArrayList<>();
    if (unplacedCount > 0) {
      issues.add("UNPLACED(" + unplacedCount + ")");
    }
    int unhealthyCount = unhealthyInstanceCount(instances);
    if (unhealthyCount > 0) {
      issues.add("UNHEALTHY(" + unhealthyCount + ")");
    }
    return issues.isEmpty() ? "HEALTHY" : String.join(",", issues);
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

  private static int intValue(Object value) {
    return value instanceof Number n ? n.intValue() : 0;
  }

  private static Object orDash(Object value) {
    return value == null ? "-" : value;
  }
}
