package com.gimle.cli;

import com.gimle.core.authz.Permission;
import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code get roles [name]}, {@code set role <name> --permission
 * <resource>:<verb>[:<tenant>[:<qualifier>]]} (repeatable), {@code delete role <name>}.
 */
public final class RolesCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public RolesCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    String name = GimleCli.requireAtMostOne(args, "role");
    if (name == null) {
      OutputFormat.printList(output, client.getList("/roles"), out);
      return;
    }
    OutputFormat.printObject(output, client.getObject("/roles/" + name), out);
  }

  /**
   * {@code --permission resource:verb[:tenant[:qualifier]]}, one per grant -- {@code resource} and
   * {@code verb} match {@code ResourceKind}/{@code Verb}'s own names case-insensitively (e.g.
   * {@code deployment:read} or {@code DEPLOYMENT:READ}), the optional third segment scopes the
   * grant to one tenant instead of cluster-wide, and the optional fourth narrows a {@code
   * custom_resource} grant to one kind ({@code custom.Greeting}) or one kind's status sub-document
   * ({@code custom.Greeting/status}). A cluster-wide grant that still needs a qualifier leaves the
   * tenant segment empty: {@code custom_resource:read::custom.Greeting}.
   *
   * <p>Any of the first three segments may be {@link Permission#ALL} instead of a name -- {@code
   * "*:read"} is read on every resource kind, {@code "deployment:*"} every verb on deployments,
   * {@code "*:*:acme"} everything within one tenant. Most shells expand a bare {@code *}, so quote
   * the argument. The wildcard is stored as a wildcard, so a grant written today covers a resource
   * kind the platform gains tomorrow with no edit to the role.
   */
  public void set(List<String> args) {
    String usage =
        "set role requires <name> --permission <resource>:<verb>[:<tenant>[:<qualifier>]] ...";
    if (args.isEmpty()) {
      throw new CliException(usage);
    }
    String name = args.get(0);
    Flags flags =
        Flags.parse(args.subList(1, args.size()), Set.of(), Set.of("--permission"), usage);
    List<String> permissionSpecs = flags.getAll("--permission");
    if (permissionSpecs.isEmpty()) {
      throw new CliException(usage);
    }

    List<Map<String, Object>> permissions = new ArrayList<>();
    for (String spec : permissionSpecs) {
      permissions.add(parsePermission(spec));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("permissions", permissions);

    client.expectSuccess(client.put("/roles/" + name, Json.write(body)));
    OutputFormat.printResult(
        output, resultBody("configured", name), "role/" + name + " configured", out);
    RbacWarnings.warnIfPlaintext(out);
  }

  /**
   * {@code apply -f <manifest.yaml>} for {@code kind: Role}. {@code permissions:} is a list of
   * {@code {resource, verb, tenantScope?, qualifier?}} mappings, already the exact wire shape --
   * {@code resource}/{@code verb} are uppercased the same way {@link #parsePermission} uppercases
   * the CLI flag form, so a manifest can write either case.
   */
  public void apply(List<String> args, PrintStream err) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    Map<String, Object> root = ManifestFiles.parseRoot(file, manifestBytes);
    String name = requireString(root, "name", file);
    if (!(root.get("permissions") instanceof List<?> rawPermissions) || rawPermissions.isEmpty()) {
      throw new CliException("manifest " + file + " requires a non-empty 'permissions' list");
    }

    List<Map<String, Object>> permissions = new ArrayList<>();
    for (Object rawPermission : rawPermissions) {
      permissions.add(normalizePermission(Json.asObject(rawPermission), file));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("permissions", permissions);

    ApiResponse response = client.put("/roles/" + name, Json.write(body));
    client.expectSuccess(response);
    ManifestFiles.printWarnings(response, err);
    OutputFormat.printResult(output, resultBody("applied", name), "role/" + name + " applied", out);
    RbacWarnings.warnIfPlaintext(out);
  }

  private static Map<String, Object> normalizePermission(Map<String, Object> raw, Path file) {
    Object resource = raw.get("resource");
    Object verb = raw.get("verb");
    if (!(resource instanceof String) || !(verb instanceof String)) {
      throw new CliException(
          "manifest " + file + "'s permissions each require a 'resource' and a 'verb'");
    }
    String tenantScope = raw.get("tenantScope") instanceof String scope ? scope : null;
    String qualifier = raw.get("qualifier") instanceof String q ? q : null;
    return toWireForm((String) resource, (String) verb, tenantScope, qualifier, "manifest " + file);
  }

  private static String requireString(Map<String, Object> root, String field, Path file) {
    if (!(root.get(field) instanceof String value) || value.isBlank()) {
      throw new CliException("manifest " + file + " has no top-level '" + field + "' field");
    }
    return value;
  }

  /**
   * The control plane cascades removal of every {@code RoleBinding} naming this Role as part of the
   * same delete -- see {@code ApiServer#handleDeleteRole}'s own javadoc for why leaving one behind
   * is a silent-reactivation trap, not just clutter. Surfacing exactly which bindings were removed
   * here, rather than a bare "deleted," is the operator-facing half of that fix: a caller has no
   * other way to learn that deleting a Role also revoked those bindings.
   */
  public void delete(String name) {
    String response = client.expectSuccess(client.delete("/roles/" + name));
    Map<String, Object> responseBody = Json.asObject(Json.parse(response));
    List<Object> removedRoleBindings = Json.asArray(responseBody.get("removedRoleBindings"));
    Map<String, Object> result = resultBody("deleted", name);
    result.put("removedRoleBindings", removedRoleBindings);
    String humanLine =
        removedRoleBindings.isEmpty()
            ? "role/" + name + " deleted"
            : "role/"
                + name
                + " deleted (also removed "
                + removedRoleBindings.size()
                + " role binding(s) that named it: "
                + removedRoleBindings
                + ")";
    OutputFormat.printResult(output, result, humanLine, out);
  }

  private static Map<String, Object> parsePermission(String spec) {
    String[] parts = spec.split(":", 4);
    if (parts.length < 2) {
      throw new CliException(
          "invalid --permission " + spec + " (expected resource:verb[:tenant[:qualifier]])");
    }
    // An empty tenant segment (custom_resource:read::custom.Greeting) means cluster-wide with a
    // qualifier -- the grant's scope and its kind-narrowing are independent axes.
    return toWireForm(
        parts[0],
        parts[1],
        parts.length >= 3 ? parts[2] : null,
        parts.length == 4 ? parts[3] : null,
        "invalid --permission " + spec);
  }

  /**
   * Resolves one grant into the wire shape, rejecting an unknown resource/verb here rather than on
   * a server round-trip. Every position goes through {@link Permission}'s own parsing, so the
   * wildcard's spelling and the set of legal names can't drift between what this CLI accepts and
   * what the control plane stores.
   */
  private static Map<String, Object> toWireForm(
      String resource, String verb, String tenant, String qualifier, String context) {
    Map<String, Object> permission = new LinkedHashMap<>();
    try {
      permission.put(
          "resource", Permission.parseResource(resource).map(Enum::name).orElse(Permission.ALL));
      permission.put("verb", Permission.parseVerb(verb).map(Enum::name).orElse(Permission.ALL));
      Permission.parseTenantScope(tenant).ifPresent(scope -> permission.put("tenantScope", scope));
      Permission.parseQualifier(qualifier).ifPresent(q -> permission.put("qualifier", q));
    } catch (IllegalArgumentException e) {
      throw new CliException(context + ": " + e.getMessage());
    }
    return permission;
  }

  private static Map<String, Object> resultBody(String result, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "role");
    body.put("name", name);
    return body;
  }
}
