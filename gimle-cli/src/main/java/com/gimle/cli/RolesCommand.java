package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code get roles [name]}, {@code set role <name> --permission <resource>:<verb>[:<tenant>]}
 * (repeatable), {@code delete role <name>}.
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
    if (args.isEmpty()) {
      OutputFormat.printList(output, client.getList("/roles"), out);
      return;
    }
    String name = args.get(0);
    OutputFormat.printObject(output, client.getObject("/roles/" + name), out);
  }

  /**
   * {@code --permission resource:verb[:tenant]}, one per grant -- {@code resource} and {@code verb}
   * match {@code ResourceKind}/{@code Verb}'s own names case-insensitively (e.g. {@code
   * deployment:read} or {@code DEPLOYMENT:READ}), the optional third segment scopes the grant to
   * one tenant instead of cluster-wide.
   */
  public void set(List<String> args) {
    String usage = "set role requires <name> --permission <resource>:<verb>[:<tenant>] ...";
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
    String[] parts = spec.split(":", 3);
    if (parts.length < 2) {
      throw new CliException("invalid --permission " + spec + " (expected resource:verb[:tenant])");
    }
    Map<String, Object> permission = new LinkedHashMap<>();
    permission.put("resource", parts[0].toUpperCase(Locale.ROOT));
    permission.put("verb", parts[1].toUpperCase(Locale.ROOT));
    if (parts.length == 3) {
      permission.put("tenantScope", parts[2]);
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
