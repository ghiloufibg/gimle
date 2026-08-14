package com.gimle.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code get statefulsets [name]}, {@code apply -f <file.yaml>}, {@code delete statefulset <name>}
 * -- mirrors {@link DeploymentsCommand} exactly, including having no dedicated {@code scale} verb:
 * like a Deployment, {@code replicas} is changed by resubmitting the manifest via {@code apply},
 * not a separate command. {@code get statefulsets <name>}'s table output surfaces each index's
 * assigned {@code nodeId} without any StatefulSet-specific rendering code -- {@code
 * OutputFormat.printObject} already renders {@code instances[].nodeId} generically, the same way it
 * already does for {@code get daemonsets <name>}, which is what makes the sticky-placement contract
 * visible to an operator here, not a separate feature to build.
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
      OutputFormat.printList(output, client.getList("/statefulsets"), out);
      return;
    }
    String name = args.get(0);
    OutputFormat.printObject(output, client.getObject("/statefulsets/" + name), out);
  }

  public void apply(List<String> args) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    String name = ManifestFiles.extractName(file, manifestBytes);
    client.expectSuccess(
        client.put("/statefulsets/" + name, new String(manifestBytes, StandardCharsets.UTF_8)));
    OutputFormat.printResult(
        output, resultBody("applied", name), "statefulset/" + name + " applied", out);
  }

  public void delete(String name) {
    client.expectSuccess(client.delete("/statefulsets/" + name));
    OutputFormat.printResult(
        output, resultBody("deleted", name), "statefulset/" + name + " deleted", out);
  }

  private static Map<String, Object> resultBody(String result, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "statefulset");
    body.put("id", name);
    return body;
  }
}
