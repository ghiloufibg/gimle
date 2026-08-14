package com.gimle.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
      OutputFormat.printList(output, client.getList("/deployments"), out);
      return;
    }
    String name = args.get(0);
    OutputFormat.printObject(output, client.getObject("/deployments/" + name), out);
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
}
