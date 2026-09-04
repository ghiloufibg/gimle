package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code get cronjobs [name]}, {@code apply -f <file.yaml>}, {@code delete cronjob <name>}, {@code
 * cronjob trigger <name>} -- mirrors {@link JobsCommand} exactly for the CRUD verbs, plus the one
 * action {@code trigger} that doesn't fit CRUD: firing a CronJob immediately, bypassing its own
 * schedule (the same operational need {@code kubectl create job --from=cronjob/x} answers). {@code
 * apply} is dispatched here by {@link GimleCli} once it has peeked at the manifest's own {@code
 * kind:} field, the same way {@link JobsCommand#apply} is.
 */
public final class CronJobsCommand {

  private static final String TENANT_USAGE =
      """
      usage: gimle get|delete cronjobs <name> [--tenant <id>]
             gimle cronjob trigger <name> [--tenant <id>]""";

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public CronJobsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    if (args.isEmpty()) {
      OutputFormat.printList(output, rows(args), out);
      return;
    }
    Map<String, Object> cronJob = client.getObject(pathFor(args));
    if (output == OutputFormat.Kind.MANIFEST) {
      out.print(ManifestExport.cronJob(cronJob));
      return;
    }
    OutputFormat.printObject(
        output, output == OutputFormat.Kind.TABLE ? humanize(cronJob) : cronJob, out);
  }

  /**
   * One snapshot's worth of rows, rendered exactly as {@link #get} would render them -- what {@code
   * --watch} re-fetches each tick and diffs against the previous one. A named CronJob yields a
   * single-row list rather than the object {@link #get} prints for it, since a watch diffs lists.
   */
  public List<Map<String, Object>> rows(List<String> args) {
    if (args.isEmpty()) {
      List<Map<String, Object>> cronJobs = client.getList("/cronjobs");
      return output == OutputFormat.Kind.TABLE ? humanizeAll(cronJobs) : cronJobs;
    }
    Map<String, Object> cronJob = client.getObject(pathFor(args));
    return List.of(output == OutputFormat.Kind.TABLE ? humanize(cronJob) : cronJob);
  }

  /**
   * Flattens one CronJob's nested status into flat table columns -- the schedule and job template
   * live a level down, so a table row rendered straight off the response held a whole nested object
   * printed as raw JSON, unlike every other workload verb's own named columns.
   */
  private static List<Map<String, Object>> humanizeAll(List<Map<String, Object>> cronJobs) {
    return cronJobs.stream().map(CronJobsCommand::humanize).toList();
  }

  private static Map<String, Object> humanize(Map<String, Object> status) {
    Map<?, ?> spec = status.get("spec") instanceof Map<?, ?> m ? m : Map.of();
    Map<?, ?> template = spec.get("jobTemplate") instanceof Map<?, ?> t ? t : Map.of();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", spec.get("name"));
    row.put("schedule", orDash(spec.get("schedule")));
    row.put("module", moduleCoordinate(template.get("moduleId")));
    row.put("tenantId", orDash(spec.get("tenantId")));
    row.put("suspend", orDash(spec.get("suspend")));
    row.put("concurrency", orDash(spec.get("concurrencyPolicy")));
    row.put("lastSchedule", orDash(status.get("lastScheduleTime")));
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

  private static String pathFor(List<String> args) {
    return TenantQuery.appendTo(
        "/cronjobs/" + args.get(0), args.subList(1, args.size()), TENANT_USAGE);
  }

  public void apply(List<String> args, PrintStream err) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    String name = ManifestFiles.extractName(file, manifestBytes);
    String body = new String(manifestBytes, StandardCharsets.UTF_8);
    if (DryRun.requested(args)) {
      DryRun.preview(client, "/cronjobs/" + name, body, output, out, err);
      return;
    }
    ApiResponse response = client.put("/cronjobs/" + name, body);
    client.expectSuccess(response);
    ManifestFiles.printWarnings(response, err);
    OutputFormat.printResult(
        output, resultBody("applied", name), "cronjob/" + name + " applied", out);
  }

  public void delete(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("missing cronjob name/id");
    }
    String name = args.get(0);
    String path =
        TenantQuery.appendTo("/cronjobs/" + name, args.subList(1, args.size()), TENANT_USAGE);
    client.expectSuccess(client.delete(path));
    OutputFormat.printResult(
        output, resultBody("deleted", name), "cronjob/" + name + " deleted", out);
  }

  /**
   * Fires {@code name} immediately, regardless of its own schedule -- {@code
   * CronJobReconciler#triggerNow}'s server-side counterpart. A 409 (concurrencyPolicy: FORBID
   * blocked it) surfaces through {@link ControlPlaneClient#expectSuccess}'s own error path the same
   * way any other rejected write does, not a special case here.
   */
  public void trigger(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("missing cronjob name/id");
    }
    String name = args.get(0);
    String path =
        TenantQuery.appendTo(
            "/cronjobs/" + name + "/trigger", args.subList(1, args.size()), TENANT_USAGE);
    String responseBody = client.expectSuccess(client.post(path, ""));
    Map<String, Object> body;
    try {
      body = Json.asObject(Json.parse(responseBody));
    } catch (IllegalArgumentException | ClassCastException e) {
      throw new CliException("unexpected response from control plane: " + e.getMessage(), e);
    }
    OutputFormat.printResult(
        output, body, "cronjob/" + name + " triggered -> job/" + body.get("jobName"), out);
  }

  private static Map<String, Object> resultBody(String result, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "cronjob");
    body.put("id", name);
    return body;
  }
}
