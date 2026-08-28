package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code secretmap list <tenantId>}, {@code secretmap get <tenantId> <name>}, {@code secretmap set
 * <tenantId> <name> [--from-literal k=v ...] [--from-file path|key=path ...]}, {@code secretmap
 * delete <tenantId> <name> [--destroy]} -- the {@code /secretmaps/*} surface, reached through
 * {@code gimle-controlplane}'s proxy to Fafnir, never Fafnir directly (matching {@link
 * SecretCommand}'s own routing decision). A distinct top-level verb, the same reasoning {@link
 * ConfigMapCommand} already gives: {@code list} here returns names scoped to one tenant object
 * kind, not the flat per-key rows {@code SecretCommand.list} returns.
 *
 * <p>Unlike {@link ConfigMapCommand#set}, {@code set} here is a single {@code PUT} with no {@code
 * expectedVersion} -- each member key keeps its own independent version ledger (see {@code
 * com.gimle.fafnir.secretmap.SecretMapStore}), so there is no single "current SecretMap version" to
 * read first. A multi-key {@code set} reports one outcome per key rather than succeeding or failing
 * as a whole -- one key's write failing never silently drops or rolls back the keys that succeeded.
 * {@code set} always merges: every existing key not named survives untouched. {@code replace
 * <tenantId> <name> [--from-literal k=v ...] [--from-file path|key=path ...]} is the explicit
 * full-replace counterpart -- every key not named is removed, so the resulting key set is exactly
 * what was given, including an empty set (which clears the SecretMap entirely).
 *
 * <p>Values cross the wire as base64, the same {@link SecretCommand} convention: {@code set} takes
 * plaintext, {@code get} prints plaintext, this class is the one place the encoding is visible.
 *
 * <p>{@code versions <tenantId> <name>} lists every stamped group version, and {@code rollback
 * <tenantId> <name> <groupVersion>} restores every key that group version recorded to its recorded
 * content or deleted state -- as a brand-new group version, never rewriting history, the same
 * forward-only semantics {@code com.gimle.fafnir.secretmap.SecretMapStore#rollback} itself
 * documents. A key added after the target group version and never part of it is left untouched.
 *
 * <p>{@code seal <tenantId> <name> --from-sealed key=path [...]} is {@code set}'s offline-sealed
 * counterpart: each {@code path} is a sealed-envelope JSON file produced by {@code gimle seal
 * value} (never plaintext, and never decoded/re-encoded here -- the file's JSON is forwarded
 * byte-for-byte under its key), and Fafnir itself unwraps and applies it. One outcome is reported
 * per key, the same shape {@code set} already prints.
 */
public final class SecretMapCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public SecretMapCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
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
      case "replace" -> replace(rest);
      case "delete" -> delete(rest);
      case "versions" -> versions(rest);
      case "rollback" -> rollback(rest);
      case "seal" -> seal(rest);
      default -> throw new CliException(usage());
    }
  }

  private void list(List<String> args) {
    String tenantId = requireOne(args, "secretmap list");
    Map<String, Object> response = client.getObject("/secretmaps/" + tenantId);
    List<Object> names = Json.asArray(response.get("names"));
    List<Map<String, Object>> rows = names.stream().map(name -> Map.of("name", name)).toList();
    OutputFormat.printList(output, rows, out);
  }

  private void get(List<String> args) {
    if (args.size() < 2) {
      throw new CliException("secretmap get requires <tenantId> <name>");
    }
    String tenantId = args.get(0);
    String name = args.get(1);
    Map<String, Object> response = client.getObject("/secretmaps/" + tenantId + "/" + name);
    OutputFormat.printList(output, Json.asObjectList(response.get("keys")), out);
  }

  private void set(List<String> args) {
    if (args.size() < 2) {
      throw new CliException(
          "secretmap set requires <tenantId> <name> [--from-literal k=v ...] [--from-file"
              + " path|key=path ...]");
    }
    String tenantId = args.get(0);
    String name = args.get(1);
    Flags flags =
        Flags.parse(
            args.subList(2, args.size()), Set.of(), Set.of("--from-literal", "--from-file"));
    Map<String, String> data = new LinkedHashMap<>();
    for (String literal : flags.getAll("--from-literal")) {
      int eq = literal.indexOf('=');
      if (eq < 0) {
        throw new CliException("--from-literal must be key=value, got: " + literal);
      }
      data.put(literal.substring(0, eq), literal.substring(eq + 1));
    }
    for (String fromFile : flags.getAll("--from-file")) {
      addFromFile(data, fromFile);
    }
    if (data.isEmpty()) {
      throw new CliException("secretmap set requires at least one --from-literal or --from-file");
    }

    Map<String, Object> body = new LinkedHashMap<>();
    Map<String, String> encoded = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : data.entrySet()) {
      encoded.put(entry.getKey(), encode(entry.getValue()));
    }
    body.put("data", encoded);
    String response =
        client.expectSuccess(client.put("/secretmaps/" + tenantId + "/" + name, Json.write(body)));
    Map<String, Object> responseBody = Json.asObject(Json.parse(response));
    OutputFormat.printList(output, Json.asObjectList(responseBody.get("results")), out);
  }

  /**
   * Full replace: unlike {@link #set}, an empty {@code data} set is valid here -- it clears the
   * SecretMap entirely, since the whole point of this verb is that the resulting key set is exactly
   * what's given, not "at least one key merged in."
   */
  private void replace(List<String> args) {
    if (args.size() < 2) {
      throw new CliException(
          "secretmap replace requires <tenantId> <name> [--from-literal k=v ...] [--from-file"
              + " path|key=path ...]");
    }
    String tenantId = args.get(0);
    String name = args.get(1);
    Flags flags =
        Flags.parse(
            args.subList(2, args.size()), Set.of(), Set.of("--from-literal", "--from-file"));
    Map<String, String> data = new LinkedHashMap<>();
    for (String literal : flags.getAll("--from-literal")) {
      int eq = literal.indexOf('=');
      if (eq < 0) {
        throw new CliException("--from-literal must be key=value, got: " + literal);
      }
      data.put(literal.substring(0, eq), literal.substring(eq + 1));
    }
    for (String fromFile : flags.getAll("--from-file")) {
      addFromFile(data, fromFile);
    }

    Map<String, Object> body = new LinkedHashMap<>();
    Map<String, String> encoded = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : data.entrySet()) {
      encoded.put(entry.getKey(), encode(entry.getValue()));
    }
    body.put("data", encoded);
    String response =
        client.expectSuccess(
            client.post("/secretmaps/" + tenantId + "/" + name + "/replace", Json.write(body)));
    Map<String, Object> responseBody = Json.asObject(Json.parse(response));
    OutputFormat.printList(output, Json.asObjectList(responseBody.get("results")), out);
  }

  private void delete(List<String> args) {
    if (args.size() < 2) {
      throw new CliException("secretmap delete requires <tenantId> <name>");
    }
    String tenantId = args.get(0);
    String name = args.get(1);
    Flags flags = Flags.parse(args.subList(2, args.size()), Set.of("--destroy"));
    boolean destroy = flags.isSet("--destroy");
    String path = "/secretmaps/" + tenantId + "/" + name + (destroy ? "?destroy=true" : "");

    client.expectSuccess(client.delete(path));
    OutputFormat.printResult(
        output,
        resultBody(destroy ? "destroyed" : "deleted", tenantId, name),
        "secretmaps/" + tenantId + "/" + name + (destroy ? " destroyed" : " deleted"),
        out);
  }

  private void versions(List<String> args) {
    if (args.size() < 2) {
      throw new CliException("secretmap versions requires <tenantId> <name>");
    }
    String tenantId = args.get(0);
    String name = args.get(1);
    Map<String, Object> response =
        client.getObject("/secretmaps/" + tenantId + "/" + name + "/versions");
    OutputFormat.printList(output, Json.asObjectList(response.get("groupVersions")), out);
  }

  private void rollback(List<String> args) {
    if (args.size() < 3) {
      throw new CliException("secretmap rollback requires <tenantId> <name> <groupVersion>");
    }
    String tenantId = args.get(0);
    String name = args.get(1);
    int groupVersion = parseGroupVersion(args.get(2));
    String response =
        client.expectSuccess(
            client.post(
                "/secretmaps/" + tenantId + "/" + name + "/rollback",
                Json.write(Map.of("groupVersion", groupVersion))));
    OutputFormat.printObject(output, Json.asObject(Json.parse(response)), out);
  }

  private static int parseGroupVersion(String raw) {
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw new CliException("groupVersion must be an integer, got: " + raw);
    }
  }

  private void seal(List<String> args) {
    if (args.size() < 2) {
      throw new CliException("secretmap seal requires <tenantId> <name> --from-sealed key=path");
    }
    String tenantId = args.get(0);
    String name = args.get(1);
    Flags flags = Flags.parse(args.subList(2, args.size()), Set.of(), Set.of("--from-sealed"));
    List<String> fromSealed = flags.getAll("--from-sealed");
    if (fromSealed.isEmpty()) {
      throw new CliException("secretmap seal requires at least one --from-sealed key=path");
    }

    Map<String, Object> sealed = new LinkedHashMap<>();
    for (String entry : fromSealed) {
      int eq = entry.indexOf('=');
      if (eq < 0) {
        throw new CliException("--from-sealed must be key=path, got: " + entry);
      }
      String key = entry.substring(0, eq);
      String path = entry.substring(eq + 1);
      sealed.put(key, Json.parse(readSealedEnvelope(path)));
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sealed", sealed);
    String response =
        client.expectSuccess(
            client.post("/secretmaps/" + tenantId + "/" + name + "/seal", Json.write(body)));
    Map<String, Object> responseBody = Json.asObject(Json.parse(response));
    OutputFormat.printList(output, Json.asObjectList(responseBody.get("results")), out);
  }

  private static String readSealedEnvelope(String path) {
    try {
      return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read --from-sealed path: " + path, e);
    }
  }

  /**
   * {@code path} alone: the key is the file's own base name. {@code key=path}: an explicit key --
   * the identical kubectl-style convention {@link ConfigMapCommand#addFromFile} already
   * establishes. A bare path with no file-name component (e.g. {@code /}) has no implicit key to
   * derive, so that case is rejected outright rather than risking a {@code NullPointerException} on
   * {@link Path#getFileName()}'s own documented {@code null} return.
   */
  private static void addFromFile(Map<String, String> data, String fromFile) {
    int eq = fromFile.indexOf('=');
    String pathText = eq < 0 ? fromFile : fromFile.substring(eq + 1);
    String key;
    if (eq < 0) {
      Path fileName = Path.of(pathText).getFileName();
      if (fileName == null) {
        throw new CliException("--from-file path has no file name to use as a key: " + pathText);
      }
      key = fileName.toString();
    } else {
      key = fromFile.substring(0, eq);
    }
    try {
      data.put(key, Files.readString(Path.of(pathText), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("could not read --from-file path: " + pathText, e);
    }
  }

  private static Map<String, Object> resultBody(String result, String tenantId, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "secretmap");
    body.put("tenantId", tenantId);
    body.put("name", name);
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

  static String usage() {
    return """
        usage: gimle secretmap <verb> [args]

        verbs:
          list <tenantId>
          get <tenantId> <name>
          set <tenantId> <name> [--from-literal key=value ...] [--from-file path|key=path ...]
          replace <tenantId> <name> [--from-literal key=value ...] [--from-file path|key=path ...]
          delete <tenantId> <name> [--destroy]
          versions <tenantId> <name>
          rollback <tenantId> <name> <groupVersion>
          seal <tenantId> <name> --from-sealed key=path [...]
        """;
  }
}
