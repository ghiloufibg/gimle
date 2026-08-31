package com.gimle.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code get jobs [name]}, {@code apply -f <file.yaml>}, {@code delete job <name>} -- mirrors
 * {@link DeploymentsCommand} exactly, including its "parse only to extract {@code name:} client-
 * side, PUT the original bytes verbatim" pattern. {@code apply} itself is dispatched here by {@link
 * GimleCli} once it has peeked at the manifest's own {@code kind:} field -- this class's own {@link
 * #apply} doesn't re-check {@code kind}, it trusts its caller already routed correctly, the same
 * way {@link DeploymentsCommand#apply} always has.
 */
public final class JobsCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public JobsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    if (args.isEmpty()) {
      OutputFormat.printList(output, client.getList("/jobs"), out);
      return;
    }
    String name = args.get(0);
    String path = TenantQuery.appendTo("/jobs/" + name, args.subList(1, args.size()));
    Map<String, Object> job = client.getObject(path);
    if (output == OutputFormat.Kind.MANIFEST) {
      out.print(ManifestExport.job(job));
      return;
    }
    OutputFormat.printObject(output, job, out);
  }

  public void apply(List<String> args, PrintStream err) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    String name = ManifestFiles.extractName(file, manifestBytes);
    ApiResponse response =
        client.put("/jobs/" + name, new String(manifestBytes, StandardCharsets.UTF_8));
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
