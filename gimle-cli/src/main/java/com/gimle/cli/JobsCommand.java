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
    OutputFormat.printObject(output, job, out);
  }

  /**
   * One snapshot's worth of rows, rendered exactly as {@link #get} would render them -- what {@code
   * --watch} re-fetches each tick and diffs against the previous one. A named job yields a
   * single-row list rather than the object {@link #get} prints for it, since a watch diffs lists.
   */
  public List<Map<String, Object>> rows(List<String> args) {
    GetCommandArgs.Split split = GetCommandArgs.split(args, Set.of("--tenant"), "job", GET_USAGE);
    if (split.name() == null) {
      return filterByTenant(client.getList("/jobs"), TenantQuery.valueOf(split.flagArgs()));
    }
    return List.of(client.getObject(pathFor(split)));
  }

  private static String pathFor(GetCommandArgs.Split split) {
    return TenantQuery.appendTo("/jobs/" + split.name(), split.flagArgs());
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
    String path = TenantQuery.appendTo("/jobs/" + name, args.subList(1, args.size()));
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
