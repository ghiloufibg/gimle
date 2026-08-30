package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code configmap list <tenantId>}, {@code configmap get <tenantId> <name>}, {@code configmap set
 * <tenantId> <name> [--from-literal k=v ...] [--from-file path|key=path ...]}, {@code configmap
 * delete <tenantId> <name>} -- the {@code /configmaps/*} surface. A distinct top-level verb, not
 * folded into {@link GimleCli}'s shared get/set/delete dispatch the way {@link ConfigCommand} is:
 * {@code list} here returns names scoped to one tenant object kind, not the flat per-key rows
 * {@code ConfigCommand.list} returns, and {@code set} needs to read-then-PATCH rather than a single
 * PUT.
 *
 * <p>{@code set} always {@code PATCH}es, supplying {@code expectedVersion} itself from a GET it
 * performs first (absent ConfigMap treated as version 0, the create case) -- a caller never types a
 * version number by hand. A concurrent writer racing this exact GET is reported back as a plain 409
 * (see {@code ControlPlaneClient.describeError}), not silently retried: the same "stale version
 * reported immediately" posture the write path itself takes.
 */
public final class ConfigMapCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public ConfigMapCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
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
      case "rollback" -> rollback(rest);
      default -> throw new CliException(usage());
    }
  }

  private void list(List<String> args) {
    String tenantId = requireOne(args, "configmap list");
    List<Object> names =
        Json.asArray(Json.parse(client.expectSuccess(client.get("/configmaps/" + tenantId))));
    List<Map<String, Object>> rows = names.stream().map(name -> Map.of("name", name)).toList();
    OutputFormat.printList(output, rows, out);
  }

  private void get(List<String> args) {
    if (args.size() < 2) {
      throw new CliException("configmap get requires <tenantId> <name>");
    }
    String tenantId = args.get(0);
    String name = args.get(1);
    Map<String, Object> response = client.getObject("/configmaps/" + tenantId + "/" + name);
    Map<String, Object> printed = new LinkedHashMap<>();
    printed.put("name", name);
    printed.put("version", response.get("version"));
    printed.put("data", response.get("data"));
    OutputFormat.printObject(output, printed, out);
  }

  private void set(List<String> args) {
    String usage =
        "configmap set requires <tenantId> <name> [--from-literal k=v ...] [--from-file"
            + " path|key=path ...]";
    if (args.size() < 2) {
      throw new CliException(usage);
    }
    String tenantId = args.get(0);
    String name = args.get(1);
    Flags flags =
        Flags.parse(
            args.subList(2, args.size()), Set.of(), Set.of("--from-literal", "--from-file"), usage);
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
      throw new CliException("configmap set requires at least one --from-literal or --from-file");
    }

    int currentVersion = currentVersion(tenantId, name);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("data", data);
    body.put("expectedVersion", currentVersion);
    String response =
        client.expectSuccess(
            client.patch("/configmaps/" + tenantId + "/" + name, Json.write(body)));
    Object version = Json.asObject(Json.parse(response)).get("version");

    Map<String, Object> resultBody = resultBody("set", tenantId, name);
    resultBody.put("version", version);
    OutputFormat.printResult(
        output,
        resultBody,
        "configmaps/" + tenantId + "/" + name + " set (version " + version + ")",
        out);
  }

  private void delete(List<String> args) {
    if (args.size() < 2) {
      throw new CliException("configmap delete requires <tenantId> <name>");
    }
    String tenantId = args.get(0);
    String name = args.get(1);
    client.expectSuccess(client.delete("/configmaps/" + tenantId + "/" + name));
    OutputFormat.printResult(
        output,
        resultBody("deleted", tenantId, name),
        "configmaps/" + tenantId + "/" + name + " deleted",
        out);
  }

  /**
   * {@code 0} (the create case) if the ConfigMap doesn't exist yet -- any other non-2xx status from
   * the GET still surfaces as a normal {@link CliException} via {@link
   * ControlPlaneClient#expectSuccess}.
   */
  private int currentVersion(String tenantId, String name) {
    ApiResponse response = client.get("/configmaps/" + tenantId + "/" + name);
    if (response.statusCode() == 404) {
      return 0;
    }
    Map<String, Object> body = Json.asObject(Json.parse(client.expectSuccess(response)));
    return ((Number) body.get("version")).intValue();
  }

  /**
   * {@code path} alone: the key is the file's own base name. {@code key=path}: an explicit key,
   * kubectl's own {@code --from-file} convention for both forms. A bare path with no file-name
   * component (e.g. {@code /}) has no implicit key to derive, so that case is rejected outright
   * rather than risking a {@code NullPointerException} on {@link Path#getFileName()}'s own
   * documented {@code null} return.
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

  private void versions(List<String> args) {
    if (args.size() < 2) {
      throw new CliException("configmap versions requires <tenantId> <name>");
    }
    String tenantId = args.get(0);
    String name = args.get(1);
    Map<String, Object> response =
        client.getObject("/configmaps/" + tenantId + "/" + name + "/versions");
    OutputFormat.printList(output, Json.asObjectList(response.get("versions")), out);
  }

  private void rollback(List<String> args) {
    if (args.size() < 3) {
      throw new CliException("configmap rollback requires <tenantId> <name> <version>");
    }
    String tenantId = args.get(0);
    String name = args.get(1);
    int version = parseVersion(args.get(2));
    String response =
        client.expectSuccess(
            client.post(
                "/configmaps/" + tenantId + "/" + name + "/rollback",
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

  private static Map<String, Object> resultBody(String result, String tenantId, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "configmap");
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

  static String usage() {
    return """
        usage: gimle configmap <verb> [args]

        verbs:
          list <tenantId>
          get <tenantId> <name>
          set <tenantId> <name> [--from-literal key=value ...] [--from-file path|key=path ...]
          delete <tenantId> <name>
          versions <tenantId> <name>
          rollback <tenantId> <name> <version>
        """;
  }
}
