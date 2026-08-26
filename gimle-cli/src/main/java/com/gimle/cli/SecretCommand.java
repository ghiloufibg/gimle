package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code secret list <tenantId>}, {@code secret get <tenantId> <key> [--version N]}, {@code secret
 * set <tenantId> <key> --value <v>}, {@code secret delete <tenantId> <key> [--destroy]}, {@code
 * secret versions <tenantId> <key>}, {@code secret rotate-key} -- the versioned {@code /secrets/*}
 * surface, reached through {@code gimle-controlplane}'s proxy to Fafnir, never Fafnir directly
 * (matching the console's own routing decision). A distinct top-level verb from {@code config} (not
 * folded into {@link GimleCli}'s shared get/set/delete dispatch the way {@link ConfigCommand} is)
 * since it needs two actions -- {@code versions}, {@code rotate-key} -- that three-verb dispatch
 * has no shape for.
 *
 * <p>Values cross the wire as base64 ({@code /secrets/*}'s own body shape is binary-safe, unlike
 * {@code /config/*}'s plain-string {@code value} field) -- this class is the one place that
 * encoding is visible at all: {@code set}/{@code get} take and print the plaintext string a caller
 * actually typed or expects to read.
 *
 * <p>{@code retire-key <keyId>} actually stops trusting a key id -- unlike {@code rotate-key}, this
 * is destructive: any value still encrypted under that id becomes permanently unrecoverable through
 * this surface from that moment on. Retiring the currently active key is rejected outright (rotate
 * first).
 */
public final class SecretCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public SecretCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void run(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException(usage());
    }
    String verb = args.get(0);
    List<String> rest = args.subList(1, args.size());
    switch (verb) {
      case "list" -> list(rest);
      case "get" -> get(rest);
      case "set" -> set(rest);
      case "delete" -> delete(rest);
      case "versions" -> versions(rest);
      case "rotate-key" -> rotateKey();
      case "retire-key" -> retireKey(rest);
      default -> throw new CliException(usage());
    }
  }

  private void list(List<String> args) {
    String tenantId = requireOne(args, "secret list");
    Map<String, Object> response = client.getObject("/secrets/" + tenantId);
    OutputFormat.printList(output, Json.asObjectList(response.get("secrets")), out);
  }

  private void get(List<String> args) {
    if (args.size() < 2) {
      throw new CliException("secret get requires <tenantId> <key>");
    }
    String tenantId = args.get(0);
    String key = args.get(1);
    Flags flags = Flags.parse(args.subList(2, args.size()), Set.of());
    String version = flags.getOrDefault("--version", null);
    String path =
        "/secrets/" + tenantId + "/" + key + (version == null ? "" : "?version=" + version);

    Map<String, Object> response = client.getObject(path);
    Map<String, Object> printed = new LinkedHashMap<>();
    printed.put("key", key);
    printed.put("version", response.get("version"));
    printed.put("value", decode((String) response.get("value")));
    OutputFormat.printObject(output, printed, out);
  }

  private void set(List<String> args) {
    if (args.size() < 2) {
      throw new CliException("secret set requires <tenantId> <key> --value <v>");
    }
    String tenantId = args.get(0);
    String key = args.get(1);
    Flags flags = Flags.parse(args.subList(2, args.size()), Set.of());
    String value = flags.get("--value");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("value", encode(value));
    String response =
        client.expectSuccess(client.put("/secrets/" + tenantId + "/" + key, Json.write(body)));
    Object version = Json.asObject(Json.parse(response)).get("version");

    Map<String, Object> resultBody = resultBody("set", tenantId, key);
    resultBody.put("version", version);
    OutputFormat.printResult(
        output,
        resultBody,
        "secrets/" + tenantId + "/" + key + " set (version " + version + ")",
        out);
  }

  private void delete(List<String> args) {
    if (args.size() < 2) {
      throw new CliException("secret delete requires <tenantId> <key>");
    }
    String tenantId = args.get(0);
    String key = args.get(1);
    Flags flags = Flags.parse(args.subList(2, args.size()), Set.of("--destroy"));
    boolean destroy = flags.isSet("--destroy");
    String path = "/secrets/" + tenantId + "/" + key + (destroy ? "?destroy=true" : "");

    client.expectSuccess(client.delete(path));
    OutputFormat.printResult(
        output,
        resultBody(destroy ? "destroyed" : "deleted", tenantId, key),
        "secrets/" + tenantId + "/" + key + (destroy ? " destroyed" : " deleted"),
        out);
  }

  private void versions(List<String> args) {
    if (args.size() < 2) {
      throw new CliException("secret versions requires <tenantId> <key>");
    }
    String tenantId = args.get(0);
    String key = args.get(1);
    Map<String, Object> response =
        client.getObject("/secrets/" + tenantId + "/" + key + "/versions");
    List<Object> versions = Json.asArray(response.get("versions"));
    List<Map<String, Object>> rows = versions.stream().map(v -> Map.of("version", v)).toList();
    OutputFormat.printList(output, rows, out);
  }

  private void rotateKey() {
    String response = client.expectSuccess(client.post("/secrets/rotate-key", ""));
    Object activeKeyId = Json.asObject(Json.parse(response)).get("activeKeyId");
    Map<String, Object> resultBody = new LinkedHashMap<>();
    resultBody.put("result", "rotated");
    resultBody.put("kind", "secret-key");
    resultBody.put("activeKeyId", activeKeyId);
    OutputFormat.printResult(
        output, resultBody, "secrets key rotated (active key id " + activeKeyId + ")", out);
  }

  private void retireKey(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("secret retire-key requires <keyId>");
    }
    int keyId = parseKeyId(args.get(0));
    String response =
        client.expectSuccess(
            client.post("/secrets/retire-key", Json.write(Map.of("keyId", keyId))));
    Object retiredKeyId = Json.asObject(Json.parse(response)).get("retiredKeyId");
    Map<String, Object> resultBody = new LinkedHashMap<>();
    resultBody.put("result", "retired");
    resultBody.put("kind", "secret-key");
    resultBody.put("retiredKeyId", retiredKeyId);
    OutputFormat.printResult(output, resultBody, "secrets key " + retiredKeyId + " retired", out);
  }

  private static int parseKeyId(String raw) {
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw new CliException("keyId must be an integer, got: " + raw);
    }
  }

  private static Map<String, Object> resultBody(String result, String tenantId, String key) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "secret");
    body.put("tenantId", tenantId);
    body.put("key", key);
    return body;
  }

  private static String requireOne(List<String> args, String what) {
    if (args.isEmpty()) {
      throw new CliException("missing tenantId for " + what);
    }
    return args.get(0);
  }

  private static String encode(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(String base64) {
    return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
  }

  static String usage() {
    return """
        usage: gimle secret <verb> [args]

        verbs:
          list <tenantId>
          get <tenantId> <key> [--version N]
          set <tenantId> <key> --value <v>
          delete <tenantId> <key> [--destroy]
          versions <tenantId> <key>
          rotate-key
          retire-key <keyId>
        """;
  }
}
