package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.nio.file.Path;
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
 *
 * <p>{@code set} also accepts {@code --add-allowed-caller-tenant}/{@code
 * --remove-allowed-caller-tenant} (and the callee equivalents), which edit one entry of an existing
 * policy's allow list instead of redeclaring the whole policy -- the difference between "let this
 * one more tenant in" and "here is the complete list of everyone allowed in," where a forgotten
 * field in the latter silently widens or narrows the policy. Either way the version guard is
 * supplied by this command from a {@code GET} it performs first, never typed by hand, so a
 * concurrent edit surfaces as a plain 409 rather than quietly overwriting the other operator's
 * change.
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
            + " --deny-all-callers] [--allowed-callee-tenant <id>... | --deny-all-callees],"
            + " or <name> --tenant <id> with only"
            + " --add-allowed-caller-tenant/--remove-allowed-caller-tenant/"
            + "--add-allowed-callee-tenant/--remove-allowed-callee-tenant to edit an existing"
            + " policy's allow list in place";
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
                "--allowed-callee-tenant",
                "--add-allowed-caller-tenant",
                "--remove-allowed-caller-tenant",
                "--add-allowed-callee-tenant",
                "--remove-allowed-callee-tenant"),
            usage);
    String tenantId = flags.getOrDefault("--tenant", null);
    if (tenantId == null || tenantId.isBlank()) {
      throw new CliException(usage);
    }
    if (hasIncrementalEdit(flags)) {
      patch(name, tenantId, flags, usage);
      return;
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

    // The version guard comes from a read this command performs itself, so a full redeclaration
    // racing another operator's edit is refused rather than silently winning.
    body.put("expectedVersion", currentVersion(tenantId, name));
    client.expectSuccess(client.post("/networkpolicies", Json.write(body)));
    OutputFormat.printResult(
        output, resultBody("configured", name), "networkpolicy/" + name + " configured", out);
  }

  private static boolean hasIncrementalEdit(Flags flags) {
    return !flags.getAll("--add-allowed-caller-tenant").isEmpty()
        || !flags.getAll("--remove-allowed-caller-tenant").isEmpty()
        || !flags.getAll("--add-allowed-callee-tenant").isEmpty()
        || !flags.getAll("--remove-allowed-callee-tenant").isEmpty();
  }

  /**
   * The in-place edit path: only the named allow-list entries move, everything else the policy
   * declares is left exactly as stored. Refuses to mix with the whole-policy flags, which would
   * make the resulting policy depend on which of the two the server applied first.
   */
  private void patch(String name, String tenantId, Flags flags, String usage) {
    if (!flags.getAll("--allowed-caller-tenant").isEmpty()
        || !flags.getAll("--allowed-callee-tenant").isEmpty()
        || !flags.getAll("--deployment").isEmpty()
        || !flags.getAll("--service-interface").isEmpty()
        || flags.isSet("--deny-all-callers")
        || flags.isSet("--deny-all-callees")) {
      throw new CliException(
          "--add-/--remove-allowed-*-tenant edit an existing policy in place and cannot be"
              + " combined with the flags that redeclare the whole policy;\n"
              + usage);
    }
    int expectedVersion = currentVersion(tenantId, name);
    if (expectedVersion == 0) {
      throw new CliException(
          "no such networkpolicy '"
              + name
              + "' under tenant "
              + tenantId
              + " to edit; declare it first with --allowed-caller-tenant/--deny-all-callers");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", expectedVersion);
    putIfAny(body, "addAllowedCallerTenantIds", flags.getAll("--add-allowed-caller-tenant"));
    putIfAny(body, "removeAllowedCallerTenantIds", flags.getAll("--remove-allowed-caller-tenant"));
    putIfAny(body, "addAllowedCalleeTenantIds", flags.getAll("--add-allowed-callee-tenant"));
    putIfAny(body, "removeAllowedCalleeTenantIds", flags.getAll("--remove-allowed-callee-tenant"));

    String response =
        client.expectSuccess(
            client.patch(
                "/networkpolicies/" + name + "?tenant=" + tenantId, Json.write(body)));
    Object version = Json.asObject(Json.parse(response)).get("version");
    Map<String, Object> resultBody = resultBody("updated", name);
    resultBody.put("version", version);
    OutputFormat.printResult(
        output, resultBody, "networkpolicy/" + name + " updated (version " + version + ")", out);
  }

  private static void putIfAny(Map<String, Object> body, String field, List<String> values) {
    if (!values.isEmpty()) {
      body.put(field, List.copyOf(new LinkedHashSet<>(values)));
    }
  }

  /** {@code 0} when no such policy exists yet -- the create case, as the API reads it. */
  private int currentVersion(String tenantId, String name) {
    ApiResponse response = client.get("/networkpolicies/" + name + "?tenant=" + tenantId);
    if (response.statusCode() == 404) {
      return 0;
    }
    Map<String, Object> body = Json.asObject(Json.parse(client.expectSuccess(response)));
    return ((Number) body.get("version")).intValue();
  }

  /**
   * {@code apply -f <manifest.yaml>} for {@code kind: NetworkPolicy}. A direction's manifest key
   * ({@code allowedCallerTenantIds}/{@code allowedCalleeTenantIds}) mirrors the wire body directly
   * -- present (even as an empty list) means restricted, absent means unrestricted -- so there is
   * no manifest equivalent of the CLI flags' separate {@code --deny-all-callers}/{@code
   * --deny-all-callees} sentinel; an empty list already means exactly that.
   */
  public void apply(List<String> args, PrintStream err) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    Map<String, Object> root = ManifestFiles.parseRoot(file, manifestBytes);
    String name = requireString(root, "name", file);
    String tenantId = requireString(root, "tenantId", file);
    boolean restrictsIngress = root.containsKey("allowedCallerTenantIds");
    boolean restrictsEgress = root.containsKey("allowedCalleeTenantIds");
    if (!restrictsIngress && !restrictsEgress) {
      throw new CliException(
          "manifest "
              + file
              + " must restrict at least one direction: declare 'allowedCallerTenantIds'"
              + " (ingress) and/or 'allowedCalleeTenantIds' (egress)");
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("tenantId", tenantId);
    if (root.get("deploymentNames") instanceof List<?> deploymentNames
        && !deploymentNames.isEmpty()) {
      body.put("deploymentNames", List.copyOf(new LinkedHashSet<>(deploymentNames)));
    }
    if (root.get("serviceInterfaceNames") instanceof List<?> serviceInterfaceNames
        && !serviceInterfaceNames.isEmpty()) {
      body.put("serviceInterfaceNames", List.copyOf(new LinkedHashSet<>(serviceInterfaceNames)));
    }
    if (restrictsIngress) {
      body.put(
          "allowedCallerTenantIds",
          List.copyOf(new LinkedHashSet<>(asStringList(root.get("allowedCallerTenantIds")))));
    }
    if (restrictsEgress) {
      body.put(
          "allowedCalleeTenantIds",
          List.copyOf(new LinkedHashSet<>(asStringList(root.get("allowedCalleeTenantIds")))));
    }

    ApiResponse response = client.post("/networkpolicies", Json.write(body));
    client.expectSuccess(response);
    ManifestFiles.printWarnings(response, err);
    OutputFormat.printResult(
        output, resultBody("applied", name), "networkpolicy/" + name + " applied", out);
  }

  private static List<String> asStringList(Object value) {
    return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
  }

  private static String requireString(Map<String, Object> root, String field, Path file) {
    if (!(root.get(field) instanceof String value) || value.isBlank()) {
      throw new CliException("manifest " + file + " has no top-level '" + field + "' field");
    }
    return value;
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
