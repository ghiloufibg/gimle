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

  /** {@code deployment revisions <name>} -- newest-first, the same order the API itself returns. */
  public void revisions(String name) {
    Map<String, Object> response = client.getObject("/deployments/" + name + "/revisions");
    OutputFormat.printList(output, Json.asObjectList(response.get("revisions")), out);
  }

  /**
   * {@code deployment rollback <name> [--to-revision N]} -- omitted {@code --to-revision} rolls
   * back to the revision immediately before the current one, matching {@code gimle-hilmir}'s own
   * {@code rollback --release <name> [--to-revision N]} default.
   */
  public void rollback(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("deployment rollback requires <name> [--to-revision N]");
    }
    String name = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of());
    Map<String, Object> body = new LinkedHashMap<>();
    String toRevision = flags.getOrDefault("--to-revision", null);
    if (toRevision != null) {
      body.put("toRevision", parseRevision(toRevision));
    }
    String response =
        client.expectSuccess(client.post("/deployments/" + name + "/rollback", Json.write(body)));
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
        "health",
        healthOf(
            intValue(status.get("unplacedCount")),
            status.get("quotaViolating"),
            status.get("limitRangeViolating")));
    return row;
  }

  private static String moduleCoordinate(Object rawModuleId) {
    if (rawModuleId instanceof Map<?, ?> moduleId) {
      return moduleId.get("name") + "@" + moduleId.get("version");
    }
    return "-";
  }

  private static String healthOf(
      int unplacedCount, Object quotaViolating, Object limitRangeViolating) {
    List<String> issues = new ArrayList<>();
    if (unplacedCount > 0) {
      issues.add("UNPLACED(" + unplacedCount + ")");
    }
    if (Boolean.TRUE.equals(quotaViolating)) {
      issues.add("QUOTA");
    }
    if (Boolean.TRUE.equals(limitRangeViolating)) {
      issues.add("LIMITRANGE");
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
