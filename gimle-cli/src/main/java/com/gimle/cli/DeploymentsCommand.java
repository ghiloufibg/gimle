package com.gimle.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code get deployments [name]}, {@code apply -f <file.yaml>}, {@code delete deployment <name>}.
 * {@code apply} reads the manifest's own top-level {@code name:} field client-side (via SnakeYAML,
 * the same library the control plane itself uses to parse it) purely to build the URL path -- the
 * original file bytes are PUT verbatim, never re-serialized, so comments/formatting survive.
 */
public final class DeploymentsCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public DeploymentsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    if (args.isEmpty()) {
      List<Map<String, Object>> deployments = client.getList("/deployments");
      // Humanization is table-only -- see NodesCommand#list's identical reasoning: -o json keeps
      // the raw spec/instances/quota shape at full fidelity for scripting.
      List<Map<String, Object>> rows =
          output == OutputFormat.Kind.TABLE ? humanizeAll(deployments) : deployments;
      OutputFormat.printList(output, rows, out);
      return;
    }
    String name = args.get(0);
    Map<String, Object> deployment = client.getObject("/deployments/" + name);
    OutputFormat.printObject(
        output, output == OutputFormat.Kind.TABLE ? humanize(deployment) : deployment, out);
  }

  public void apply(List<String> args) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    String name = ManifestFiles.extractName(file, manifestBytes);
    client.expectSuccess(
        client.put("/deployments/" + name, new String(manifestBytes, StandardCharsets.UTF_8)));
    OutputFormat.printResult(
        output, resultBody("applied", name), "deployment/" + name + " applied", out);
  }

  public void delete(String name) {
    client.expectSuccess(client.delete("/deployments/" + name));
    OutputFormat.printResult(
        output, resultBody("deleted", name), "deployment/" + name + " deleted", out);
  }

  private static Map<String, Object> resultBody(String result, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "deployment");
    body.put("id", name);
    return body;
  }

  private static List<Map<String, Object>> humanizeAll(List<Map<String, Object>> deployments) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> deployment : deployments) {
      rows.add(humanize(deployment));
    }
    return rows;
  }

  /**
   * Flattens one deployment status's nested {@code spec}/{@code instances}/quota fields into
   * table-column-friendly derived fields -- module coordinate, placed-vs-desired replica count, and
   * a rollup health status -- the same shape the console computes from this identical data (see
   * {@code gimle-console/src/routes/deployments.index.tsx}).
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
    row.put(
        "health", healthOf(intValue(status.get("unplacedCount")), status.get("quotaViolating")));
    return row;
  }

  private static String moduleCoordinate(Object rawModuleId) {
    if (rawModuleId instanceof Map<?, ?> moduleId) {
      return moduleId.get("name") + "@" + moduleId.get("version");
    }
    return "-";
  }

  private static String healthOf(int unplacedCount, Object quotaViolating) {
    List<String> issues = new ArrayList<>();
    if (unplacedCount > 0) {
      issues.add("UNPLACED(" + unplacedCount + ")");
    }
    if (Boolean.TRUE.equals(quotaViolating)) {
      issues.add("QUOTA");
    }
    return issues.isEmpty() ? "HEALTHY" : String.join(",", issues);
  }

  private static int intValue(Object value) {
    return value instanceof Number n ? n.intValue() : 0;
  }

  private static Object orDash(Object value) {
    return value == null ? "-" : value;
  }
}
