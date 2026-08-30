package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code get config <tenantId>}, {@code set config <tenantId> <key> <value> [--encrypted]}, {@code
 * delete config <tenantId> <key>} -- folded into {@link GimleCli}'s shared get/set/delete dispatch.
 * {@code config versions <tenantId> <key>} and {@code config rollback <tenantId> <key> <version>}
 * reach the plaintext version ledger {@code ConfigVersionStore} added instead, through a narrower
 * top-level {@code config} verb {@code GimleCli} routes directly (see its own javadoc for why get/
 * set/delete themselves stay folded rather than moving here too) -- neither has any shape in the
 * three-verb dispatch, and an encrypted key was never covered by that ledger to begin with (see
 * {@code ConfigVersionStore}'s own javadoc).
 */
public final class ConfigCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public ConfigCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void list(String tenantId) {
    OutputFormat.printList(output, client.getList("/config/" + tenantId), out);
  }

  public void set(List<String> args) {
    String usage = "set config requires <tenantId> <key> <value> [--encrypted]";
    if (args.size() < 3) {
      throw new CliException(usage);
    }
    String tenantId = args.get(0);
    String key = args.get(1);
    String value = args.get(2);
    Flags flags = Flags.parse(args.subList(3, args.size()), Set.of("--encrypted"), usage);
    boolean encrypted = flags.isSet("--encrypted");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("value", value);
    body.put("encrypted", encrypted);
    client.expectSuccess(client.put("/config/" + tenantId + "/" + key, Json.write(body)));
    OutputFormat.printResult(
        output, resultBody("set", tenantId, key), "config/" + tenantId + "/" + key + " set", out);
  }

  public void delete(List<String> args) {
    if (args.size() < 2) {
      throw new CliException("delete config requires <tenantId> <key>");
    }
    String tenantId = args.get(0);
    String key = args.get(1);
    client.expectSuccess(client.delete("/config/" + tenantId + "/" + key));
    OutputFormat.printResult(
        output,
        resultBody("deleted", tenantId, key),
        "config/" + tenantId + "/" + key + " deleted",
        out);
  }

  void versions(List<String> args) {
    if (args.size() < 2) {
      throw new CliException("config versions requires <tenantId> <key>");
    }
    String tenantId = args.get(0);
    String key = args.get(1);
    Map<String, Object> response =
        client.getObject("/config/" + tenantId + "/" + key + "/versions");
    OutputFormat.printList(output, Json.asObjectList(response.get("versions")), out);
  }

  void rollback(List<String> args) {
    if (args.size() < 3) {
      throw new CliException("config rollback requires <tenantId> <key> <version>");
    }
    String tenantId = args.get(0);
    String key = args.get(1);
    int version = parseVersion(args.get(2));
    String response =
        client.expectSuccess(
            client.post(
                "/config/" + tenantId + "/" + key + "/rollback",
                Json.write(Map.of("version", version))));
    OutputFormat.printObject(output, Json.asObject(Json.parse(response)), out);
  }

  private static int parseVersion(String raw) {
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw new CliException("version must be an integer, got: " + raw);
    }
  }

  private static Map<String, Object> resultBody(String result, String tenantId, String key) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "config");
    body.put("tenantId", tenantId);
    body.put("key", key);
    return body;
  }
}
