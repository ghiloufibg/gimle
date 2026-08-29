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
 * <name>...] [--service-interface <fqcn>...] [--allowed-caller-tenant <id>... | --deny-all-callers]
 * [--allowed-callee-tenant <id>... | --deny-all-callees]}, {@code delete networkpolicy <name>} --
 * the NetworkPolicy analogue's CLI surface: a declared, deny-by-default restriction on which other
 * tenants may call into one tenant's own Services (ingress) and which tenants that tenant's own
 * workloads may call out to (egress). A direction is only restricted when expressed: naming {@code
 * --allowed-caller-tenant}s (or {@code --deny-all-callers}, the allow-nobody form of the same
 * direction) restricts ingress, {@code --allowed-callee-tenant}/{@code --deny-all-callees} likewise
 * restricts egress, and at least one direction must be expressed -- the same "a policy must
 * restrict something" rule the API itself enforces. {@code set} POSTs to the bare {@code
 * /networkpolicies} collection, the same routing {@link ServicesCommand} documents for its own
 * sibling network-model resource. Unlike a Service's {@code tenantId}, a NetworkPolicy's {@code
 * tenantId} is never optional -- it restricts exactly one tenant's own traffic, so {@code --tenant}
 * is required, not optional.
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
    String path = requireTenantScopedPath("/networkpolicies/" + name, args.subList(1, args.size()));
    OutputFormat.printObject(output, client.getObject(path), out);
  }

  /**
   * Unlike every other by-name resource here, a NetworkPolicy has no untenanted namespace to fall
   * back to (its own {@code tenantId} is never optional -- see this class's own javadoc), so the
   * control plane rejects a GET/DELETE with no {@code ?tenant=} outright. Caught here instead, with
   * a clearer message than the API's own 400 -- the same "fail fast, locally" posture {@link #set}
   * already takes for its own required {@code --tenant}.
   */
  private static String requireTenantScopedPath(String path, List<String> argsAfterName) {
    String withTenant = TenantQuery.appendTo(path, argsAfterName);
    if (withTenant.equals(path)) {
      throw new CliException("networkpolicy requires --tenant <id>");
    }
    return withTenant;
  }

  public void set(List<String> args) {
    String usage =
        "set networkpolicy requires <name> --tenant <id> [--deployment <name>...]"
            + " [--service-interface <fqcn>...] [--allowed-caller-tenant <id>... |"
            + " --deny-all-callers] [--allowed-callee-tenant <id>... | --deny-all-callees]";
    if (args.isEmpty()) {
      throw new CliException(usage);
    }
    String name = args.get(0);
    Flags flags =
        Flags.parse(
            args.subList(1, args.size()),
            Set.of("--deny-all-callers", "--deny-all-callees"),
            Set.of(
                "--deployment",
                "--service-interface",
                "--allowed-caller-tenant",
                "--allowed-callee-tenant"),
            usage);
    String tenantId = flags.getOrDefault("--tenant", null);
    if (tenantId == null || tenantId.isBlank()) {
      throw new CliException(usage);
    }
    List<String> deploymentNames = flags.getAll("--deployment");
    List<String> serviceInterfaceNames = flags.getAll("--service-interface");
    List<String> allowedCallerTenantIds = flags.getAll("--allowed-caller-tenant");
    List<String> allowedCalleeTenantIds = flags.getAll("--allowed-callee-tenant");
    if (!allowedCallerTenantIds.isEmpty() && flags.isSet("--deny-all-callers")) {
      throw new CliException("--deny-all-callers cannot be combined with --allowed-caller-tenant");
    }
    if (!allowedCalleeTenantIds.isEmpty() && flags.isSet("--deny-all-callees")) {
      throw new CliException("--deny-all-callees cannot be combined with --allowed-callee-tenant");
    }
    boolean restrictsIngress =
        !allowedCallerTenantIds.isEmpty() || flags.isSet("--deny-all-callers");
    boolean restrictsEgress =
        !allowedCalleeTenantIds.isEmpty() || flags.isSet("--deny-all-callees");
    if (!restrictsIngress && !restrictsEgress) {
      throw new CliException(
          "a network policy must restrict at least one direction: pass --allowed-caller-tenant/"
              + "--deny-all-callers (ingress) and/or --allowed-callee-tenant/--deny-all-callees"
              + " (egress)");
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("tenantId", tenantId);
    if (!deploymentNames.isEmpty()) {
      body.put("deploymentNames", List.copyOf(new LinkedHashSet<>(deploymentNames)));
    }
    if (!serviceInterfaceNames.isEmpty()) {
      body.put("serviceInterfaceNames", List.copyOf(new LinkedHashSet<>(serviceInterfaceNames)));
    }
    // A direction is serialized only when restricted: the API reads a missing field as "this
    // direction is unrestricted" and a present-but-empty array as "deny every cross-tenant peer".
    if (restrictsIngress) {
      body.put("allowedCallerTenantIds", List.copyOf(new LinkedHashSet<>(allowedCallerTenantIds)));
    }
    if (restrictsEgress) {
      body.put("allowedCalleeTenantIds", List.copyOf(new LinkedHashSet<>(allowedCalleeTenantIds)));
    }

    client.expectSuccess(client.post("/networkpolicies", Json.write(body)));
    OutputFormat.printResult(
        output, resultBody("configured", name), "networkpolicy/" + name + " configured", out);
  }

  public void delete(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("missing networkpolicy name/id");
    }
    String name = args.get(0);
    String path = requireTenantScopedPath("/networkpolicies/" + name, args.subList(1, args.size()));
    client.expectSuccess(client.delete(path));
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
