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

  private static final String GET_USAGE = "usage: gimle get deployments [name] [--tenant <id>]";

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public DeploymentsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    GetCommandArgs.Split split =
        GetCommandArgs.split(args, Set.of("--tenant"), "deployment", GET_USAGE);
    if (split.name() == null) {
      OutputFormat.printList(output, rows(args), out);
      return;
    }
    Map<String, Object> deployment = client.getObject(pathFor(split));
    if (output == OutputFormat.Kind.MANIFEST) {
      out.print(ManifestExport.deployment(deployment));
      return;
    }
    OutputFormat.printObject(
        output, output == OutputFormat.Kind.TABLE ? humanize(deployment) : deployment, out);
  }

  /**
   * One snapshot's worth of rows, rendered exactly as {@link #get} would render them -- what {@code
   * --watch} re-fetches each tick and diffs against the previous one. A named deployment yields a
   * single-row list rather than the object {@link #get} prints for it, since a watch diffs lists.
   */
  public List<Map<String, Object>> rows(List<String> args) {
    GetCommandArgs.Split split =
        GetCommandArgs.split(args, Set.of("--tenant"), "deployment", GET_USAGE);
    if (split.name() == null) {
      List<Map<String, Object>> deployments =
          filterByTenant(client.getList("/deployments"), TenantQuery.valueOf(split.flagArgs()));
      // Humanization is table-only -- see NodesCommand#list's identical reasoning: -o json keeps
      // the raw spec/instances/quota shape at full fidelity for scripting.
      return output == OutputFormat.Kind.TABLE ? humanizeAll(deployments) : deployments;
    }
    Map<String, Object> deployment = client.getObject(pathFor(split));
    return List.of(output == OutputFormat.Kind.TABLE ? humanize(deployment) : deployment);
  }

  private static String pathFor(GetCommandArgs.Split split) {
    return TenantQuery.appendTo("/deployments/" + split.name(), split.flagArgs());
  }

  /**
   * The control plane's own {@code /deployments} list route has no {@code ?tenant=} filter of its
   * own (it returns everything the caller is authorized to read across every tenant) -- {@code
   * --tenant} on the list form is applied here instead, against each entry's own {@code
   * spec.tenantId}, absent for an untenanted deployment and therefore never matched by a non-null
   * filter.
   */
  private static List<Map<String, Object>> filterByTenant(
      List<Map<String, Object>> deployments, String tenantId) {
    if (tenantId == null) {
      return deployments;
    }
    List<Map<String, Object>> filtered = new ArrayList<>();
    for (Map<String, Object> deployment : deployments) {
      if (deployment.get("spec") instanceof Map<?, ?> spec
          && tenantId.equals(spec.get("tenantId"))) {
        filtered.add(deployment);
      }
    }
    return filtered;
  }

  public void apply(List<String> args, PrintStream err) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    String name = ManifestFiles.extractName(file, manifestBytes);
    String body = new String(manifestBytes, StandardCharsets.UTF_8);
    if (DryRun.requested(args)) {
      DryRun.preview(client, "/deployments/" + name, body, output, out, err);
      return;
    }
    ApiResponse response = client.put("/deployments/" + name, body);
    client.expectSuccess(response);
    ManifestFiles.printWarnings(response, err);
    OutputFormat.printResult(
        output, resultBody("applied", name), "deployment/" + name + " applied", out);
  }

  public void delete(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("missing deployment name/id");
    }
    String name = args.get(0);
    String path = TenantQuery.appendTo("/deployments/" + name, args.subList(1, args.size()));
    client.expectSuccess(client.delete(path));
    OutputFormat.printResult(
        output, resultBody("deleted", name), "deployment/" + name + " deleted", out);
  }

  /**
   * {@code deployment revisions <name> [--tenant <id>]} -- newest-first, the same order the API
   * itself returns.
   */
  public void revisions(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("missing deployment name/id");
    }
    String name = args.get(0);
    String path =
        TenantQuery.appendTo("/deployments/" + name + "/revisions", args.subList(1, args.size()));
    Map<String, Object> response = client.getObject(path);
    OutputFormat.printList(output, Json.asObjectList(response.get("revisions")), out);
  }

  /**
   * {@code deployment rollback <name> [--to-revision N] [--tenant <id>]} -- omitted {@code
   * --to-revision} rolls back to the revision immediately before the current one, matching {@code
   * gimle-hilmir}'s own {@code rollback --release <name> [--to-revision N]} default.
   */
  public void rollback(List<String> args) {
    String usage = "deployment rollback requires <name> [--to-revision N] [--tenant <id>]";
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
    String path =
        TenantQuery.appendTo("/deployments/" + name + "/rollback", args.subList(1, args.size()));
    String response = client.expectSuccess(client.post(path, Json.write(body)));
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
            status.get("limitRangeViolating"),
            status.get("instances")));
    return row;
  }

  private static String moduleCoordinate(Object rawModuleId) {
    if (rawModuleId instanceof Map<?, ?> moduleId) {
      return moduleId.get("name") + "@" + moduleId.get("version");
    }
    return "-";
  }

  private static String healthOf(
      int unplacedCount, Object quotaViolating, Object limitRangeViolating, Object instances) {
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
    int unhealthyCount = unhealthyInstanceCount(instances);
    if (unhealthyCount > 0) {
      issues.add("UNHEALTHY(" + unhealthyCount + ")");
    }
    return issues.isEmpty() ? "HEALTHY" : String.join(",", issues);
  }

  /**
   * Counts placed instances whose own agent-reported observation is unhealthy: either explicitly
   * {@code "alive": false}, or {@code lifecycleState == "FAILED"} -- the same definition {@code
   * HealthReconciler#isHealthy} already uses server-side. An unrecognized or transient {@code
   * lifecycleState} (e.g. still {@code STARTING} right after a deploy) is deliberately not flagged,
   * to avoid false positives on a freshly-deploying instance; {@code FAILED} is not transient, so
   * it's flagged even when {@code alive} still reads {@code true}.
   */
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
