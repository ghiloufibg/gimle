package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code get ingresses [<name>] [--tenant <id>]}, {@code delete ingress <name> [--tenant <id>]},
 * and {@code apply -f <manifest.yaml>} for {@code kind: Ingress} -- the declarative replacement for
 * the only way a gateway route is declared.
 *
 * <p>There is deliberately no {@code set ingress} verb building a route list from flags. A route
 * carries up to six fields whose meaning depends on its kind, and expressing several of them on one
 * command line would be strictly worse to read than the manifest this kind exists to accept.
 *
 * <p>Every {@code apply} carries a version guard, supplied by this command rather than typed by
 * hand, so an edit made against a revision someone else has already moved past is refused instead
 * of silently replacing a change its author never saw -- the same posture {@code set networkpolicy}
 * takes for its own whole-object writes.
 */
public final class IngressCommand {

  private static final String TENANT_USAGE =
      "usage: gimle get|delete ingresses [name] [--tenant <id>]";

  private static final String GET_USAGE = "usage: gimle get ingresses [name] [--tenant <id>]";

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public IngressCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    GetCommandArgs.Split split =
        GetCommandArgs.split(args, Set.of("--tenant"), "ingress", GET_USAGE);
    if (split.name() == null) {
      // The collection route answers with an array; only the by-name route answers with a single
      // object, so the two forms cannot share one response shape.
      List<Map<String, Object>> ingresses =
          filterByTenant(
              client.getList("/ingresses"), TenantQuery.valueOf(split.flagArgs(), TENANT_USAGE));
      OutputFormat.printList(output, ingresses, out);
      return;
    }
    String path =
        TenantQuery.appendTo("/ingresses/" + split.name(), split.flagArgs(), TENANT_USAGE);
    OutputFormat.printObject(output, client.getObject(path), out);
  }

  /**
   * An Ingress's own JSON shape carries {@code tenantId} at the top level rather than nested under
   * a {@code spec} object, the same way a Service's does -- an Ingress isn't status-wrapped the way
   * a workload kind is -- so the filter reads it directly.
   */
  private static List<Map<String, Object>> filterByTenant(
      List<Map<String, Object>> ingresses, String tenantId) {
    if (tenantId == null) {
      return ingresses;
    }
    List<Map<String, Object>> filtered = new ArrayList<>();
    for (Map<String, Object> ingress : ingresses) {
      if (tenantId.equals(ingress.get("tenantId"))) {
        filtered.add(ingress);
      }
    }
    return filtered;
  }

  public void delete(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("delete ingress requires <name> [--tenant <id>]");
    }
    String name = args.get(0);
    client.expectSuccess(
        client.delete(
            TenantQuery.appendTo(
                "/ingresses/" + name, args.subList(1, args.size()), TENANT_USAGE)));
    OutputFormat.printResult(
        output, resultBody("deleted", name), "ingress/" + name + " deleted", out);
  }

  /** {@code apply -f <manifest.yaml>} for {@code kind: Ingress}. */
  public void apply(List<String> args, PrintStream err) {
    Path file = ManifestFiles.requireFileFlag(args);
    Map<String, Object> root = ManifestFiles.parseRoot(file, ManifestFiles.readManifestBytes(file));
    if (root.get("name") == null || root.get("tenantId") == null) {
      throw new CliException("manifest " + file + " must declare 'name' and 'tenantId'");
    }
    if (Json.asObjectList(root.get("routes")).isEmpty()) {
      throw new CliException("manifest " + file + " must declare at least one route");
    }
    String name = String.valueOf(root.get("name"));
    String tenantId = String.valueOf(root.get("tenantId"));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", root.get("name"));
    body.put("tenantId", root.get("tenantId"));
    List<Map<String, Object>> routes = new ArrayList<>();
    for (Map<String, Object> declared : Json.asObjectList(root.get("routes"))) {
      Map<String, Object> route = new LinkedHashMap<>(declared);
      // Normalized here rather than server-side so a manifest may write `kind: service` in the same
      // lower-case style the rest of a route line uses.
      route.computeIfPresent(
          "kind", (unused, kind) -> String.valueOf(kind).toUpperCase(Locale.ROOT));
      routes.add(route);
    }
    body.put("routes", routes);
    int expectedVersion = guardVersion(file, root, name, tenantId);
    body.put("expectedVersion", expectedVersion);
    ApiResponse response = client.post("/ingresses", Json.write(body));
    if (response.statusCode() == 409) {
      throw staleEdit(name, tenantId, expectedVersion, response);
    }
    client.expectSuccess(response);
    ManifestFiles.printWarnings(response, err);
    OutputFormat.printResult(
        output, resultBody("configured", name), "ingress/" + name + " configured", out);
  }

  /**
   * The stored version this apply is allowed to replace. A manifest carrying the {@code version}
   * that {@code get ingress} printed guards against exactly the revision its author edited, so a
   * second operator's change made in between is never overwritten unseen. A manifest declaring no
   * version guards against whatever is stored at this instant, read here rather than typed: that
   * still refuses a write landing between this read and the POST below, and it keeps the counter
   * from advancing past a revision no client ever saw.
   */
  private int guardVersion(Path file, Map<String, Object> root, String name, String tenantId) {
    Object declared = root.get("version");
    if (declared == null) {
      return currentVersion(name, tenantId);
    }
    if (!(declared instanceof Number version)) {
      throw new CliException("manifest " + file + " declares a non-numeric 'version': " + declared);
    }
    return version.intValue();
  }

  /** {@code 0} when no such ingress exists yet -- the create case, as the API reads it. */
  private int currentVersion(String name, String tenantId) {
    ApiResponse response = client.get("/ingresses/" + name + "?tenant=" + tenantId);
    if (response.statusCode() == 404) {
      return 0;
    }
    Map<String, Object> body = Json.asObject(Json.parse(client.expectSuccess(response)));
    return ((Number) body.get("version")).intValue();
  }

  /**
   * The 409 the version guard produces, rewritten as the one thing the operator has to do next. The
   * raw body names only a number; what is actually lost is their edit, so the message says so and
   * names the command that rebases it.
   */
  private static CliException staleEdit(
      String name, String tenantId, int expectedVersion, ApiResponse response) {
    return CliException.conflict(
        "ingress/"
            + name
            + " changed while this apply was being prepared: it expected version "
            + expectedVersion
            + " but the stored ingress is now at version "
            + storedVersion(response)
            + ". Re-read it with 'gimle get ingress "
            + name
            + " --tenant "
            + tenantId
            + "', re-apply your edit on top, and submit again -- applying as-is would discard the"
            + " other write.");
  }

  private static String storedVersion(ApiResponse response) {
    try {
      Object current = Json.asObject(Json.parse(response.body())).get("currentVersion");
      return current == null ? "unknown" : String.valueOf(current);
    } catch (RuntimeException e) {
      return "unknown";
    }
  }

  private static Map<String, Object> resultBody(String verb, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(verb, true);
    body.put("name", name);
    return body;
  }
}
