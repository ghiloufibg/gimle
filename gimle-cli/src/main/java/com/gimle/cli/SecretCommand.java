package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code secret list <tenantId>}, {@code secret get <tenantId> <key> [--version N]}, {@code secret
 * set <tenantId> <key> --value <v>}, {@code secret delete <tenantId> <key> [--destroy]}, {@code
 * secret undelete <tenantId> <key> [--version N]}, {@code secret versions <tenantId> <key>}, {@code
 * secret rotate-key} -- the versioned {@code /secrets/*} surface, reached through {@code
 * gimle-controlplane}'s proxy to Fafnir, never Fafnir directly (matching the console's own routing
 * decision). A distinct top-level verb from {@code config} (not folded into {@link GimleCli}'s
 * shared get/set/delete dispatch the way {@link ConfigCommand} is) since it needs actions -- {@code
 * versions}, {@code undelete}, {@code rotate-key} -- that three-verb dispatch has no shape for.
 *
 * <p>{@code undelete} clears a soft delete's flag in place rather than minting a new version: with
 * no {@code --version}, it restores whatever version was current when {@code delete} was called;
 * with one, it restores that specific earlier version's data as current instead, without disturbing
 * any other version's own stored data. It cannot bring back a {@code --destroy}ed secret -- that
 * data is genuinely gone.
 *
 * <p>Values cross the wire as base64 ({@code /secrets/*}'s own body shape is binary-safe, unlike
 * {@code /config/*}'s plain-string {@code value} field) -- this class is the one place that
 * encoding is visible at all: {@code set}/{@code get} take and print the plaintext string a caller
 * actually typed or expects to read.
 *
 * <p>{@code set} takes an optional {@code --type}: with none, the value is stored exactly as
 * before, unexamined. Declaring one ({@code pem-certificate}, {@code pem-private-key}) validates
 * the value's shape at write time, so a truncated or wrongly-encoded PEM fails at the call that
 * wrote it rather than at the module launch that later tries to parse it. The type is remembered
 * per version and shown by {@code get}/{@code versions}.
 *
 * <p>{@code export}/{@code import} are the bulk pair, for moving a tenant's whole secret set to a
 * freshly-bootstrapped cluster whose master key can't open the old cluster's ciphertext. The export
 * file holds plaintext secret material -- see {@code #export}'s own javadoc for what this command
 * does about that, and what it leaves to the operator.
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
      case "undelete" -> undelete(rest);
      case "versions" -> versions(rest);
      case "export" -> export(rest);
      case "import" -> importSecrets(rest);
      case "rotate-key" -> rotateKey();
      case "rewrap" -> rewrap();
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
    String usage = "secret get requires <tenantId> <key> [--version N]";
    if (args.size() < 2) {
      throw new CliException(usage);
    }
    String tenantId = args.get(0);
    String key = args.get(1);
    Flags flags = Flags.parseKnown(args.subList(2, args.size()), Set.of("--version"), usage);
    String version = flags.getOrDefault("--version", null);
    String path =
        "/secrets/" + tenantId + "/" + key + (version == null ? "" : "?version=" + version);

    Map<String, Object> response;
    try {
      response = client.getObject(path);
    } catch (CliException e) {
      throw explainMissingVersion(e, tenantId, key, version);
    }
    Map<String, Object> printed = new LinkedHashMap<>();
    printed.put("key", key);
    printed.put("version", response.get("version"));
    printed.put("value", decode((String) response.get("value")));
    OutputFormat.printObject(output, printed, out);
  }

  /**
   * Turns the server's own "no such secret" into the real reason whenever the key does exist and it
   * was the requested version that didn't. Both cases answer 404 there, and the server is right to:
   * it is asked for one {@code key@version} address, which genuinely isn't there. But the two mean
   * opposite things to a caller -- "you never created this" versus "you asked for version 9 of
   * something that stops at 3" -- so the distinction is drawn here, where the version the caller
   * asked for is still known, by re-reading the key's own version list. A failure of that second
   * read changes nothing: the original 404 is what gets thrown.
   */
  private CliException explainMissingVersion(
      CliException original, String tenantId, String key, String version) {
    if (version == null || original.exitCode() != CliExitCode.NOT_FOUND) {
      return original;
    }
    List<Map<String, Object>> versions;
    try {
      versions =
          Json.asObjectList(
              client.getObject("/secrets/" + tenantId + "/" + key + "/versions").get("versions"));
    } catch (RuntimeException ignored) {
      return original;
    }
    if (versions.isEmpty()) {
      return original;
    }
    String known =
        versions.stream()
            .map(v -> String.valueOf(v.get("version")))
            .collect(Collectors.joining(", "));
    return CliException.notFound(
        "not found: secrets/"
            + tenantId
            + "/"
            + key
            + " has no version "
            + version
            + "; existing versions: "
            + known);
  }

  private void set(List<String> args) {
    String usage =
        "secret set requires <tenantId> <key> (--value <v> | --from-file <path>) [--type <t>]";
    if (args.size() < 2) {
      throw new CliException(usage);
    }
    String tenantId = args.get(0);
    String key = args.get(1);
    Flags flags =
        Flags.parseKnown(
            args.subList(2, args.size()), Set.of("--value", "--from-file", "--type"), usage);
    String value = readValue(flags, usage);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("value", encode(value));
    // Omitted entirely rather than sent as "opaque": an absent type is exactly what the server
    // reads as the untyped default, so there is nothing for the caller to spell out.
    String type = flags.getOrDefault("--type", null);
    if (type != null) {
      body.put("type", type);
    }
    String response =
        client.expectSuccess(client.put("/secrets/" + tenantId + "/" + key, Json.write(body)));
    Map<String, Object> parsed = Json.asObject(Json.parse(response));
    Object version = parsed.get("version");

    Map<String, Object> resultBody = resultBody("set", tenantId, key);
    resultBody.put("version", version);
    resultBody.put("type", parsed.get("type"));
    OutputFormat.printResult(
        output,
        resultBody,
        "secrets/" + tenantId + "/" + key + " set (version " + version + ")",
        out);
  }

  /**
   * {@code --from-file} exists for the typed values: a PEM certificate or private key is multi-line
   * material nobody can reasonably paste into {@code --value}, and shell-quoting it is exactly the
   * step that mangles it into the malformed value {@code --type} then rejects.
   */
  private static String readValue(Flags flags, String usage) {
    String inline = flags.getOrDefault("--value", null);
    String fromFile = flags.getOrDefault("--from-file", null);
    if ((inline == null) == (fromFile == null)) {
      throw new CliException("exactly one of --value or --from-file is required" + "\n\n" + usage);
    }
    if (inline != null) {
      return inline;
    }
    try {
      return Files.readString(Path.of(fromFile), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read --from-file path: " + fromFile, e);
    }
  }

  private void delete(List<String> args) {
    String usage = "secret delete requires <tenantId> <key> [--destroy]";
    if (args.size() < 2) {
      throw new CliException(usage);
    }
    String tenantId = args.get(0);
    String key = args.get(1);
    Flags flags =
        Flags.parseKnown(
            args.subList(2, args.size()),
            Set.of("--destroy"),
            Set.of(),
            Set.of("--destroy"),
            usage);
    boolean destroy = flags.isSet("--destroy");
    String path = "/secrets/" + tenantId + "/" + key + (destroy ? "?destroy=true" : "");

    client.expectSuccess(client.delete(path));
    OutputFormat.printResult(
        output,
        resultBody(destroy ? "destroyed" : "deleted", tenantId, key),
        "secrets/" + tenantId + "/" + key + (destroy ? " destroyed" : " deleted"),
        out);
  }

  private void undelete(List<String> args) {
    String usage = "secret undelete requires <tenantId> <key> [--version N]";
    if (args.size() < 2) {
      throw new CliException(usage);
    }
    String tenantId = args.get(0);
    String key = args.get(1);
    Flags flags = Flags.parseKnown(args.subList(2, args.size()), Set.of("--version"), usage);
    String version = flags.getOrDefault("--version", null);
    String path =
        "/secrets/"
            + tenantId
            + "/"
            + key
            + "/undelete"
            + (version == null ? "" : "?version=" + version);

    String response = client.expectSuccess(client.post(path, ""));
    Object restoredVersion = Json.asObject(Json.parse(response)).get("version");

    Map<String, Object> resultBody = resultBody("undeleted", tenantId, key);
    resultBody.put("version", restoredVersion);
    OutputFormat.printResult(
        output,
        resultBody,
        "secrets/" + tenantId + "/" + key + " undeleted (version " + restoredVersion + ")",
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
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> version : Json.asObjectList(response.get("versions"))) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("version", version.get("version"));
      row.put("author", version.get("author"));
      row.put("writtenAt", formatTimestamp(version.get("writtenAtEpochMilli")));
      row.put("type", version.get("type"));
      rows.add(row);
    }
    OutputFormat.printList(output, rows, out);
  }

  private static String formatTimestamp(Object epochMilli) {
    return epochMilli instanceof Number number
        ? Instant.ofEpochMilli(number.longValue()).toString()
        : "-";
  }

  /**
   * {@code secret export <tenantId> --out <file>} -- every live secret the tenant owns, values
   * included, in one authorized and audited round trip, so migrating a tenant to a freshly
   * bootstrapped cluster (whose master key can't open the old cluster's ciphertext) doesn't mean
   * scripting a get-then-set loop per key.
   *
   * <p>The file it writes holds plaintext secret material, base64-encoded but not encrypted, and
   * that is deliberate rather than an oversight: the whole purpose of the export is to carry values
   * to a cluster with a different master key, so ciphertext under the source cluster's key would be
   * useless at the destination. Three things follow, all enforced here rather than left to the
   * operator: the destination is a file, never stdout, so secret material never lands in a terminal
   * scrollback or a shell pipeline by accident; the file is created with owner-only permissions
   * wherever the filesystem supports POSIX ones; and an existing path is refused outright rather
   * than silently overwritten. It remains the operator's job to delete it once imported -- treat it
   * exactly like the key file itself.
   */
  private void export(List<String> args) {
    String usage = "secret export requires <tenantId> --out <file>";
    if (args.isEmpty()) {
      throw new CliException(usage);
    }
    String tenantId = args.get(0);
    Flags flags = Flags.parseKnown(args.subList(1, args.size()), Set.of("--out"), usage);
    Path destination = Path.of(flags.get("--out"));

    Map<String, Object> listed = client.getObject("/secrets/" + tenantId);
    List<String> keys =
        Json.asObjectList(listed.get("secrets")).stream().map(s -> (String) s.get("key")).toList();
    Map<String, Object> secrets = new LinkedHashMap<>();
    if (!keys.isEmpty()) {
      Map<String, Object> fetched =
          client.getObject("/secrets/" + tenantId + "?names=" + String.join(",", keys));
      secrets.putAll(Json.asObject(fetched.get("secrets")));
    }

    Map<String, Object> document = new LinkedHashMap<>();
    document.put("tenantId", tenantId);
    document.put("exportedAtEpochMilli", System.currentTimeMillis());
    document.put("secrets", secrets);
    writeExportFile(destination, Json.write(document));

    Map<String, Object> resultBody = new LinkedHashMap<>();
    resultBody.put("result", "exported");
    resultBody.put("kind", "secret");
    resultBody.put("tenantId", tenantId);
    resultBody.put("count", secrets.size());
    resultBody.put("file", destination.toString());
    OutputFormat.printResult(
        output,
        resultBody,
        secrets.size()
            + " secret(s) exported to "
            + destination
            + " -- this file holds plaintext secret material; delete it once imported",
        out);
  }

  private static void writeExportFile(Path destination, String contents) {
    try {
      if (Files.exists(destination)) {
        throw new CliException(
            "refusing to overwrite an existing export file: "
                + destination
                + " (secret material is never silently replaced)");
      }
      if (destination.getFileSystem().supportedFileAttributeViews().contains("posix")) {
        Files.createFile(
            destination,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      }
      Files.writeString(destination, contents, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write export file: " + destination, e);
    }
  }

  /**
   * {@code secret import <tenantId> --in <file>} -- the other half of {@link #export}. Each key is
   * written through the ordinary single-key write path, so every one is separately authorized and
   * separately audited (and lands as a new version at the destination rather than pretending to
   * restore the source's version numbers, which mean nothing in another cluster's ledger). Each
   * key's declared type travels with it and is re-validated on arrival.
   */
  private void importSecrets(List<String> args) {
    String usage = "secret import requires <tenantId> --in <file>";
    if (args.isEmpty()) {
      throw new CliException(usage);
    }
    String tenantId = args.get(0);
    Flags flags = Flags.parseKnown(args.subList(1, args.size()), Set.of("--in"), usage);
    Path source = Path.of(flags.get("--in"));

    Map<String, Object> document = readExportFile(source);
    Map<String, Object> secrets = Json.asObject(document.get("secrets"));
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map.Entry<String, Object> entry : secrets.entrySet()) {
      Map<String, Object> exported = Json.asObject(entry.getValue());
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("value", exported.get("value"));
      body.put("type", exported.get("type"));
      String response =
          client.expectSuccess(
              client.put("/secrets/" + tenantId + "/" + entry.getKey(), Json.write(body)));
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("key", entry.getKey());
      row.put("version", Json.asObject(Json.parse(response)).get("version"));
      rows.add(row);
    }
    OutputFormat.printList(output, rows, out);
  }

  private static Map<String, Object> readExportFile(Path source) {
    try {
      return Json.asObject(Json.parse(Files.readString(source, StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException("could not read export file: " + source, e);
    }
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

  private void rewrap() {
    String response = client.expectSuccess(client.post("/secrets/rewrap", ""));
    Map<String, Object> body = Json.asObject(Json.parse(response));
    Object rewrapped = body.get("rewrapped");
    Map<String, Object> resultBody = new LinkedHashMap<>();
    resultBody.put("result", "rewrapped");
    resultBody.put("kind", "secret");
    resultBody.put("rewrapped", rewrapped);
    resultBody.put("activeKeyId", body.get("activeKeyId"));
    OutputFormat.printResult(
        output,
        resultBody,
        "re-encrypted "
            + rewrapped
            + " secret value(s) under active key "
            + body.get("activeKeyId"),
        out);
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
          set <tenantId> <key> (--value <v> | --from-file <path>) [--type <t>]
          delete <tenantId> <key> [--destroy]
          undelete <tenantId> <key> [--version N]
          versions <tenantId> <key>
          export <tenantId> --out <file>
          import <tenantId> --in <file>
          rotate-key
          retire-key <keyId>

        secret types (--type, default opaque):
          opaque, pem-certificate, pem-private-key
        """;
  }
}
