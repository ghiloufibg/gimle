package com.gimle.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code get jobs [name]}, {@code apply -f <file.yaml>}, {@code delete job <name>} -- mirrors
 * {@link DeploymentsCommand} exactly, including its "parse only to extract {@code name:} client-
 * side, PUT the original bytes verbatim" pattern. {@code apply} itself is dispatched here by {@link
 * GimleCli} once it has peeked at the manifest's own {@code kind:} field -- this class's own {@link
 * #apply} doesn't re-check {@code kind}, it trusts its caller already routed correctly, the same
 * way {@link DeploymentsCommand#apply} always has.
 */
public final class JobsCommand {

  private static final String TENANT_USAGE = "usage: gimle get|delete jobs <name> [--tenant <id>]";

  private static final String GET_USAGE = "usage: gimle get jobs [name] [--tenant <id>]";

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public JobsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    GetCommandArgs.Split split = GetCommandArgs.split(args, Set.of("--tenant"), "job", GET_USAGE);
    if (split.name() == null) {
      OutputFormat.printList(output, rows(args), out);
      return;
    }
    Map<String, Object> job = client.getObject(pathFor(split));
    if (output == OutputFormat.Kind.MANIFEST) {
      out.print(ManifestExport.job(job));
      return;
    }
    OutputFormat.printObject(output, output == OutputFormat.Kind.TABLE ? humanize(job) : job, out);
  }

  /**
   * One snapshot's worth of rows, rendered exactly as {@link #get} would render them -- what {@code
   * --watch} re-fetches each tick and diffs against the previous one. A named job yields a
   * single-row list rather than the object {@link #get} prints for it, since a watch diffs lists.
   */
  public List<Map<String, Object>> rows(List<String> args) {
    GetCommandArgs.Split split = GetCommandArgs.split(args, Set.of("--tenant"), "job", GET_USAGE);
    if (split.name() == null) {
      List<Map<String, Object>> jobs =
          filterByTenant(
              client.getList("/jobs"), TenantQuery.valueOf(split.flagArgs(), TENANT_USAGE));
      return output == OutputFormat.Kind.TABLE ? humanizeAll(jobs) : jobs;
    }
    Map<String, Object> job = client.getObject(pathFor(split));
    return List.of(output == OutputFormat.Kind.TABLE ? humanize(job) : job);
  }

  /**
   * Flattens one job's nested status into the flat columns a table can actually render. Without
   * this a table cell held a whole {@code spec}/{@code currentRun} object printed as raw JSON --
   * unreadable, and unlike every other workload verb, whose own table has named columns.
   */
  private static List<Map<String, Object>> humanizeAll(List<Map<String, Object>> jobs) {
    return jobs.stream().map(JobsCommand::humanize).toList();
  }

  private static Map<String, Object> humanize(Map<String, Object> status) {
    Map<?, ?> spec = status.get("spec") instanceof Map<?, ?> m ? m : Map.of();
    Map<?, ?> currentRun = status.get("currentRun") instanceof Map<?, ?> r ? r : Map.of();
    Map<?, ?> observation = currentRun.get("observation") instanceof Map<?, ?> o ? o : Map.of();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", spec.get("name"));
    row.put("module", moduleCoordinate(spec.get("moduleId")));
    row.put("tenantId", orDash(spec.get("tenantId")));
    row.put("phase", orDash(status.get("phase")));
    row.put("attempt", orDash(currentRun.get("attempt")));
    row.put("node", orDash(currentRun.get("nodeId")));
    // A terminal job carries a reason instead of a live observation, and a running one the other
    // way round -- one column shows whichever of the two this job actually has.
    row.put(
        "state",
        observation.get("lifecycleState") != null
            ? String.valueOf(observation.get("lifecycleState"))
            : orDash(currentRun.get("reason")));
    return row;
  }

  private static String moduleCoordinate(Object rawModuleId) {
    if (rawModuleId instanceof Map<?, ?> moduleId) {
      return moduleId.get("name") + "@" + moduleId.get("version");
    }
    return "-";
  }

  private static String orDash(Object value) {
    return value == null || String.valueOf(value).isBlank() ? "-" : String.valueOf(value);
  }

  private static String pathFor(GetCommandArgs.Split split) {
    return TenantQuery.appendTo("/jobs/" + split.name(), split.flagArgs(), TENANT_USAGE);
  }

  /**
   * Mirrors {@code DeploymentsCommand#filterByTenant}: the {@code /jobs} list route has no {@code
   * ?tenant=} filter of its own, so {@code --tenant} on the list form is applied here against each
   * entry's own {@code spec.tenantId}.
   */
  private static List<Map<String, Object>> filterByTenant(
      List<Map<String, Object>> jobs, String tenantId) {
    if (tenantId == null) {
      return jobs;
    }
    List<Map<String, Object>> filtered = new ArrayList<>();
    for (Map<String, Object> job : jobs) {
      if (job.get("spec") instanceof Map<?, ?> spec && tenantId.equals(spec.get("tenantId"))) {
        filtered.add(job);
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
      DryRun.preview(client, "/jobs/" + name, body, output, out, err);
      return;
    }
    ApiResponse response = client.put("/jobs/" + name, body);
    client.expectSuccess(response);
    ManifestFiles.printWarnings(response, err);
    OutputFormat.printResult(output, resultBody("applied", name), "job/" + name + " applied", out);
  }

  public void delete(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("missing job name/id");
    }
    String name = args.get(0);
    String path = TenantQuery.appendTo("/jobs/" + name, args.subList(1, args.size()), TENANT_USAGE);
    client.expectSuccess(client.delete(path));
    OutputFormat.printResult(output, resultBody("deleted", name), "job/" + name + " deleted", out);
  }

  private static Map<String, Object> resultBody(String result, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "job");
    body.put("id", name);
    return body;
  }
}
