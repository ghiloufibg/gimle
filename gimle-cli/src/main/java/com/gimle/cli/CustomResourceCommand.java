package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The generic custom-kind surface: {@code apply -f} for {@code kind: KindDefinition} and for any
 * kind the built-in dispatch doesn't recognize (PUT of the verbatim manifest bytes -- the server
 * owns validation, and an undefined kind comes back as its 400 carrying the definition catalog),
 * {@code gimle kinds}, and {@code get}/{@code delete} for custom kinds with the noun resolved
 * against the live catalog in a fixed order: exact prefixed kind name, then a definition's declared
 * {@code plural}, then its {@code shortNames} -- one {@code GET /kinddefinitions} feeds the whole
 * resolution, fetched at most once per invocation.
 *
 * <p>A lost compare-and-set race (the server's 409 ending in "re-fetch and retry") is retried a
 * bounded number of times -- each retry re-sends the same manifest, and the server re-runs its own
 * read-validate-propose cycle against the new current generation -- surfacing the conflict only
 * once the bound is exhausted. Any other 409 (a violator list, a declared-name collision) is a real
 * refusal a resend can't fix and is surfaced immediately.
 */
public final class CustomResourceCommand {

  private static final String TENANT_USAGE =
      "usage: gimle get|delete <custom-kind> [name] [--tenant <id>]";

  private static final int MAX_CONFLICT_RETRIES = 3;

  /** The retryable-conflict marker both concurrent-modification 409 messages end with. */
  private static final String RETRYABLE_CONFLICT_MARKER = "re-fetch and retry";

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  private List<Map<String, Object>> cachedCatalog;

  public CustomResourceCommand(
      ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  // ---- apply ----

  /** {@code apply -f} for a {@code kind: KindDefinition} manifest. */
  public void applyKindDefinition(List<String> args, PrintStream err) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    String name = ManifestFiles.extractName(file, manifestBytes);
    ApiResponse response =
        putWithBoundedConflictRetry(
            "/kinddefinitions/" + encode(name), new String(manifestBytes, StandardCharsets.UTF_8));
    client.expectSuccess(response);
    ManifestFiles.printWarnings(response, err);
    OutputFormat.printResult(
        output,
        resultBody("applied", "kinddefinition", name),
        "kinddefinition/" + name + " applied",
        out);
  }

  /**
   * {@code apply -f} for an instance of {@code kind} -- the fallthrough for any kind the built-in
   * dispatch doesn't recognize. The manifest bytes go up verbatim; whether {@code kind} actually
   * names a defined custom kind is the server's call, made against its live catalog.
   */
  public void apply(String kind, List<String> args, PrintStream err) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    String name = ManifestFiles.extractName(file, manifestBytes);
    ApiResponse response =
        putWithBoundedConflictRetry(
            "/resources/" + encode(kind) + "/" + encode(name),
            new String(manifestBytes, StandardCharsets.UTF_8));
    client.expectSuccess(response);
    ManifestFiles.printWarnings(response, err);
    OutputFormat.printResult(
        output, resultBody("applied", kind, name), kind + "/" + name + " applied", out);
  }

  private ApiResponse putWithBoundedConflictRetry(String path, String body) {
    ApiResponse response = client.put(path, body);
    for (int retry = 0; retry < MAX_CONFLICT_RETRIES; retry++) {
      if (response.statusCode() != 409 || !response.body().contains(RETRYABLE_CONFLICT_MARKER)) {
        return response;
      }
      response = client.put(path, body);
    }
    return response;
  }

  // ---- gimle kinds ----

  /** The definition catalog: prefixed name, declared names, scope, instance count, description. */
  public void kinds() {
    List<Map<String, Object>> catalog = catalog();
    if (output == OutputFormat.Kind.JSON) {
      OutputFormat.printList(output, catalog, out);
      return;
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> definition : catalog) {
      String kindName = String.valueOf(definition.get("kindName"));
      Map<String, Object> names = Json.asObject(definition.getOrDefault("names", Map.of()));
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("KIND", kindName);
      row.put("SCOPE", definition.get("scope"));
      row.put("PLURAL", names.get("plural"));
      row.put(
          "SHORTNAMES",
          names.get("shortNames") instanceof List<?> shortNames && !shortNames.isEmpty()
              ? String.join(",", shortNames.stream().map(String::valueOf).toList())
              : null);
      row.put("INSTANCES", client.getList("/resources/" + encode(kindName)).size());
      row.put("DESCRIPTION", definition.get("description"));
      rows.add(row);
    }
    OutputFormat.printList(output, rows, out);
  }

  // ---- get / delete with noun resolution ----

  /**
   * Whether {@code noun} resolves to a defined custom kind -- {@code GimleCli}'s three-verb
   * dispatch asks this only after every built-in noun has already failed to match, so a custom kind
   * can never shadow a built-in resource name.
   */
  public Optional<String> resolveKind(String noun) {
    List<Map<String, Object>> catalog = catalog();
    for (Map<String, Object> definition : catalog) {
      if (noun.equals(definition.get("kindName"))) {
        return Optional.of(noun);
      }
    }
    for (Map<String, Object> definition : catalog) {
      Map<String, Object> names = Json.asObject(definition.getOrDefault("names", Map.of()));
      if (noun.equals(names.get("plural"))) {
        return Optional.of(String.valueOf(definition.get("kindName")));
      }
    }
    for (Map<String, Object> definition : catalog) {
      Map<String, Object> names = Json.asObject(definition.getOrDefault("names", Map.of()));
      if (names.get("shortNames") instanceof List<?> shortNames && shortNames.contains(noun)) {
        return Optional.of(String.valueOf(definition.get("kindName")));
      }
    }
    return Optional.empty();
  }

  public void get(String kindName, List<String> args) {
    if (args.isEmpty() || args.get(0).startsWith("--")) {
      String path = TenantQuery.appendTo("/resources/" + encode(kindName), args, TENANT_USAGE);
      printResources(kindName, client.getList(path));
      return;
    }
    String name = args.get(0);
    String path =
        TenantQuery.appendTo(
            "/resources/" + encode(kindName) + "/" + encode(name),
            args.subList(1, args.size()),
            TENANT_USAGE);
    printResources(kindName, List.of(client.getObject(path)));
  }

  /** {@code delete kinddefinition <kind>} -- refused server-side while instances exist. */
  public void deleteKindDefinition(String kind) {
    client.expectSuccess(client.delete("/kinddefinitions/" + encode(kind)));
    OutputFormat.printResult(
        output,
        resultBody("deleted", "kinddefinition", kind),
        "kinddefinition/" + kind + " deleted",
        out);
  }

  public void delete(String kindName, List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("missing resource name");
    }
    String name = args.get(0);
    String path =
        TenantQuery.appendTo(
            "/resources/" + encode(kindName) + "/" + encode(name),
            args.subList(1, args.size()),
            TENANT_USAGE);
    client.expectSuccess(client.delete(path));
    OutputFormat.printResult(
        output, resultBody("deleted", kindName, name), kindName + "/" + name + " deleted", out);
  }

  /**
   * Tables render NAME/TENANT/GENERATION plus the definition's own declared printColumns, each
   * resolved by dotted path into the resource's spec/status; {@code -o json} emits the resources
   * verbatim, spec and status untouched.
   */
  private void printResources(String kindName, List<Map<String, Object>> resources) {
    if (output == OutputFormat.Kind.JSON) {
      if (resources.size() == 1) {
        OutputFormat.printObject(output, resources.get(0), out);
      } else {
        OutputFormat.printList(output, resources, out);
      }
      return;
    }
    List<Map<String, Object>> printColumns = printColumnsOf(kindName);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> resource : resources) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("NAME", resource.get("name"));
      row.put("TENANT", resource.get("tenantId"));
      row.put("GENERATION", resource.get("generation"));
      for (Map<String, Object> column : printColumns) {
        row.put(
            String.valueOf(column.get("name")),
            resolvePath(resource, String.valueOf(column.get("path"))));
      }
      rows.add(row);
    }
    OutputFormat.printList(output, rows, out);
  }

  private List<Map<String, Object>> printColumnsOf(String kindName) {
    for (Map<String, Object> definition : catalog()) {
      if (kindName.equals(definition.get("kindName"))) {
        Object columns = definition.get("printColumns");
        return columns instanceof List<?> ? Json.asObjectList(columns) : List.of();
      }
    }
    return List.of();
  }

  /**
   * Walks {@code path}'s dot-separated segments into nested objects -- {@code status.timesSaid} --
   * returning {@code null} (a {@code -} cell) the moment any segment is missing or non-object: an
   * unresolved column is an empty cell, never an error.
   */
  private static Object resolvePath(Map<String, Object> root, String path) {
    Object current = root;
    for (String segment : path.split("\\.")) {
      if (!(current instanceof Map<?, ?> map)) {
        return null;
      }
      current = map.get(segment);
    }
    return current;
  }

  private List<Map<String, Object>> catalog() {
    if (cachedCatalog == null) {
      cachedCatalog = client.getList("/kinddefinitions");
    }
    return cachedCatalog;
  }

  private static String encode(String segment) {
    return URLEncoder.encode(segment, StandardCharsets.UTF_8);
  }

  private static Map<String, Object> resultBody(String result, String kind, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", kind);
    body.put("name", name);
    return body;
  }
}
