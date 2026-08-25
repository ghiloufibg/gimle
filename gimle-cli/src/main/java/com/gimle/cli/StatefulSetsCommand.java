package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    if (args.isEmpty()) {
      throw new CliException("statefulset rollback requires <name> [--to-revision N]");
    }
    String name = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of());
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
}
