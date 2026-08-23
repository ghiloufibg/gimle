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
 * {@code get daemonsets [name]}, {@code apply -f <file.yaml>}, {@code delete daemonset <name>} --
 * mirrors {@link DeploymentsCommand}/{@link JobsCommand} exactly. Deliberately no {@code
 * scale}-equivalent verb: a DaemonSet's replica count isn't operator-settable the way a
 * Deployment's is, it's topology-derived (one per eligible node) -- there is nothing here for a
 * scale verb to set. {@code apply} itself is dispatched here by {@link GimleCli} once it has peeked
 * at the manifest's own {@code kind:} field, the same way every other kind's is.
 */
public final class DaemonSetsCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public DaemonSetsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    if (args.isEmpty()) {
      OutputFormat.printList(output, client.getList("/daemonsets"), out);
      return;
    }
    String name = args.get(0);
    OutputFormat.printObject(output, client.getObject("/daemonsets/" + name), out);
  }

  public void apply(List<String> args) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    String name = ManifestFiles.extractName(file, manifestBytes);
    client.expectSuccess(
        client.put("/daemonsets/" + name, new String(manifestBytes, StandardCharsets.UTF_8)));
    OutputFormat.printResult(
        output, resultBody("applied", name), "daemonset/" + name + " applied", out);
  }

  public void delete(String name) {
    client.expectSuccess(client.delete("/daemonsets/" + name));
    OutputFormat.printResult(
        output, resultBody("deleted", name), "daemonset/" + name + " deleted", out);
  }

  /** {@code daemonset revisions <name>} -- mirrors {@link DeploymentsCommand#revisions}. */
  public void revisions(String name) {
    Map<String, Object> response = client.getObject("/daemonsets/" + name + "/revisions");
    OutputFormat.printList(output, Json.asObjectList(response.get("revisions")), out);
  }

  /**
   * {@code daemonset rollback <name> [--to-revision N]} -- mirrors {@link
   * DeploymentsCommand#rollback}.
   */
  public void rollback(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("daemonset rollback requires <name> [--to-revision N]");
    }
    String name = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of());
    Map<String, Object> body = new LinkedHashMap<>();
    String toRevision = flags.getOrDefault("--to-revision", null);
    if (toRevision != null) {
      body.put("toRevision", parseRevision(toRevision));
    }
    String response =
        client.expectSuccess(client.post("/daemonsets/" + name + "/rollback", Json.write(body)));
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
    body.put("kind", "daemonset");
    body.put("id", name);
    return body;
  }
}
