package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code get networkpolicies [name]}, {@code set networkpolicy <name> --tenant <id> [--deployment
 * <name>...] --allowed-caller-tenant <id> [--allowed-caller-tenant <id>...]}, {@code delete
 * networkpolicy <name>} -- the NetworkPolicy analogue's CLI surface: a declared, deny-by-default
 * restriction on which other tenants may call into one tenant's own Services. {@code set} POSTs to
 * the bare {@code /networkpolicies} collection, the same routing {@link ServicesCommand} documents
 * for its own sibling network-model resource. Unlike a Service's {@code tenantId}, a
 * NetworkPolicy's {@code tenantId} is never optional -- it restricts exactly one tenant's own
 * Services, so {@code --tenant} is required, not optional.
 */
public final class NetworkPolicyCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public NetworkPolicyCommand(
      ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    if (args.isEmpty()) {
      OutputFormat.printList(output, client.getList("/networkpolicies"), out);
      return;
    }
    String name = args.get(0);
    OutputFormat.printObject(output, client.getObject("/networkpolicies/" + name), out);
  }

  public void set(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("set networkpolicy requires <name>");
    }
    String name = args.get(0);
    Flags flags =
        Flags.parse(
            args.subList(1, args.size()),
            Set.of(),
            Set.of("--deployment", "--allowed-caller-tenant"));
    String tenantId = flags.getOrDefault("--tenant", null);
    if (tenantId == null || tenantId.isBlank()) {
      throw new CliException("set networkpolicy requires --tenant");
    }
    List<String> deploymentNames = flags.getAll("--deployment");
    List<String> allowedCallerTenantIds = flags.getAll("--allowed-caller-tenant");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("tenantId", tenantId);
    if (!deploymentNames.isEmpty()) {
      body.put("deploymentNames", List.copyOf(new LinkedHashSet<>(deploymentNames)));
    }
    body.put("allowedCallerTenantIds", List.copyOf(new LinkedHashSet<>(allowedCallerTenantIds)));

    client.expectSuccess(client.post("/networkpolicies", Json.write(body)));
    OutputFormat.printResult(
        output, resultBody("configured", name), "networkpolicy/" + name + " configured", out);
  }

  public void delete(String name) {
    client.expectSuccess(client.delete("/networkpolicies/" + name));
    OutputFormat.printResult(
        output, resultBody("deleted", name), "networkpolicy/" + name + " deleted", out);
  }

  private static Map<String, Object> resultBody(String result, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "networkpolicy");
    body.put("name", name);
    return body;
  }
}
