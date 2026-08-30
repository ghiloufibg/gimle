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
    if (args.isEmpty()) {
      OutputFormat.printList(output, client.getList("/roles"), out);
      return;
    }
    String name = args.get(0);
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

  public void delete(String name) {
    client.expectSuccess(client.delete("/roles/" + name));
    OutputFormat.printResult(output, resultBody("deleted", name), "role/" + name + " deleted", out);
  }

  private static Map<String, Object> parsePermission(String spec) {
    String[] parts = spec.split(":", 4);
    if (parts.length < 2) {
      throw new CliException(
          "invalid --permission " + spec + " (expected resource:verb[:tenant[:qualifier]])");
    }
    Map<String, Object> permission = new LinkedHashMap<>();
    permission.put("resource", parts[0].toUpperCase(Locale.ROOT));
    permission.put("verb", parts[1].toUpperCase(Locale.ROOT));
    // An empty tenant segment (custom_resource:read::custom.Greeting) means cluster-wide with a
    // qualifier -- the grant's scope and its kind-narrowing are independent axes.
    if (parts.length >= 3 && !parts[2].isEmpty()) {
      permission.put("tenantScope", parts[2]);
    }
    if (parts.length == 4 && !parts[3].isEmpty()) {
      permission.put("qualifier", parts[3]);
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
