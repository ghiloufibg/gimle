package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code get services [name]}, {@code set service <name> (--deployment <name> [--deployment
 * <name>...] | --external-name <host>) --port <n> [--target-port <n>] [--tenant <id>]
 * [--session-affinity] [--protocol tcp|udp]}, {@code delete service <name>}, {@code service
 * endpoints <name>} -- the ClusterIP analogue's CLI surface. {@code set} POSTs to the bare {@code
 * /services} collection rather than PUTting {@code /services/<name>}, matching {@code ApiServer}'s
 * own routing: a {@link com.gimle.mimir.manifest.ServiceSpec} names itself in the request body,
 * unlike every {@code WorkloadSpec} kind's own {@code PUT /deployments/{name}}-shaped routes.
 */
public final class ServicesCommand {

  private static final String TENANT_USAGE =
      """
      usage: gimle get|delete services <name> [--tenant <id>]
             gimle service endpoints <name> [--tenant <id>]""";

  private static final String GET_USAGE = "usage: gimle get services [name] [--tenant <id>]";

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public ServicesCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    GetCommandArgs.Split split =
        GetCommandArgs.split(args, Set.of("--tenant"), "service", GET_USAGE);
    if (split.name() == null) {
      List<Map<String, Object>> services =
          filterByTenant(
              client.getList("/services"), TenantQuery.valueOf(split.flagArgs(), TENANT_USAGE));
      OutputFormat.printList(output, services, out);
      return;
    }
    String path = TenantQuery.appendTo("/services/" + split.name(), split.flagArgs(), TENANT_USAGE);
    OutputFormat.printObject(output, client.getObject(path), out);
  }

  /**
   * Unlike {@code DeploymentsCommand}/{@code JobsCommand}, a Service's own JSON shape has {@code
   * tenantId} at the top level rather than nested under a {@code spec} object -- a Service isn't
   * status-wrapped the way a workload kind is -- so the filter reads it directly.
   */
  private static List<Map<String, Object>> filterByTenant(
      List<Map<String, Object>> services, String tenantId) {
    if (tenantId == null) {
      return services;
    }
    List<Map<String, Object>> filtered = new ArrayList<>();
    for (Map<String, Object> service : services) {
      if (tenantId.equals(service.get("tenantId"))) {
        filtered.add(service);
      }
    }
    return filtered;
  }

  public void set(List<String> args, PrintStream err) {
    String usage =
        "set service requires <name> (--deployment <name> [--deployment <name>...] |"
            + " --external-name <host>) --port <n> [--target-port <n>] [--tenant <id>]"
            + " [--session-affinity] [--protocol tcp|udp]";
    if (args.isEmpty()) {
      throw new CliException(usage);
    }
    String name = args.get(0);
    Flags flags =
        Flags.parse(
            args.subList(1, args.size()),
            Set.of("--session-affinity"),
            Set.of("--deployment"),
            usage);
    List<String> deploymentNames = flags.getAll("--deployment");
    String externalName = flags.getOrDefault("--external-name", null);
    if (deploymentNames.isEmpty() && externalName == null) {
      throw new CliException(usage);
    }
    long port = flags.requireLong("--port");
    String targetPortValue = flags.getOrDefault("--target-port", null);
    String tenantId = flags.getOrDefault("--tenant", null);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    if (tenantId != null) {
      body.put("tenantId", tenantId);
    }
    body.put("deploymentNames", List.copyOf(new LinkedHashSet<>(deploymentNames)));
    body.put("port", port);
    // Omitted, not defaulted to --port: an absent targetPort means "route to whatever single port
    // the instance reports", which is what an ephemeral-port workload needs, while a declared one
    // is matched exactly against the instance's reported ports.
    if (targetPortValue != null) {
      body.put("targetPort", parsePort(targetPortValue));
    }
    if (flags.isSet("--session-affinity")) {
      body.put("sessionAffinity", true);
    }
    if (externalName != null) {
      body.put("externalName", externalName);
    }
    String protocol = flags.getOrDefault("--protocol", null);
    if (protocol != null) {
      String upper = protocol.toUpperCase(Locale.ROOT);
      if (!"TCP".equals(upper) && !"UDP".equals(upper)) {
        throw new CliException("--protocol must be tcp or udp");
      }
      body.put("protocol", upper);
    }

    ApiResponse response = client.post("/services", Json.write(body));
    client.expectSuccess(response);
    ManifestFiles.printWarnings(response, err);
    OutputFormat.printResult(
        output, resultBody("configured", name), "service/" + name + " configured", out);
  }

  /**
   * {@code apply -f <manifest.yaml>} for {@code kind: Service} -- builds the identical JSON body
   * {@link #set} builds from flags, from manifest fields instead, and POSTs it to the same {@code
   * /services} route. A Service has no {@code PUT /services/{name}}-shaped route to PUT the YAML
   * bytes to verbatim the way the workload kinds' own {@code apply} does.
   */
  public void apply(List<String> args, PrintStream err) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    Map<String, Object> root = ManifestFiles.parseRoot(file, manifestBytes);
    String name = requireString(root, "name", file);
    Object port = root.get("port");
    if (port == null) {
      throw new CliException("manifest " + file + " requires a top-level 'port' field");
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    Object tenantId = root.get("tenantId");
    if (tenantId != null) {
      body.put("tenantId", tenantId);
    }
    List<?> deploymentNames = root.get("deploymentNames") instanceof List<?> l ? l : List.of();
    body.put("deploymentNames", List.copyOf(new LinkedHashSet<>(deploymentNames)));
    body.put("port", port);
    if (root.get("targetPort") != null) {
      body.put("targetPort", root.get("targetPort"));
    }
    if (Boolean.TRUE.equals(root.get("sessionAffinity"))) {
      body.put("sessionAffinity", true);
    }
    Object protocol = root.get("protocol");
    if (protocol != null) {
      body.put("protocol", String.valueOf(protocol).toUpperCase(Locale.ROOT));
    }
    Object externalName = root.get("externalName");
    if (externalName != null) {
      body.put("externalName", externalName);
    }

    ApiResponse response = client.post("/services", Json.write(body));
    client.expectSuccess(response);
    ManifestFiles.printWarnings(response, err);
    OutputFormat.printResult(
        output, resultBody("applied", name), "service/" + name + " applied", out);
  }

  private static String requireString(Map<String, Object> root, String field, Path file) {
    if (!(root.get(field) instanceof String value) || value.isBlank()) {
      throw new CliException("manifest " + file + " has no top-level '" + field + "' field");
    }
    return value;
  }

  public void delete(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("missing service name/id");
    }
    String name = args.get(0);
    String path =
        TenantQuery.appendTo("/services/" + name, args.subList(1, args.size()), TENANT_USAGE);
    client.expectSuccess(client.delete(path));
    OutputFormat.printResult(
        output, resultBody("deleted", name), "service/" + name + " deleted", out);
  }

  /**
   * {@code service endpoints <name> [--tenant <id>]} -- the live, reconciler-independent endpoint
   * set.
   */
  public void endpoints(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("missing service name/id");
    }
    String name = args.get(0);
    String path =
        TenantQuery.appendTo(
            "/services/" + name + "/endpoints", args.subList(1, args.size()), TENANT_USAGE);
    OutputFormat.printObject(output, client.getObject(path), out);
  }

  private static long parsePort(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new CliException("--target-port must be a number: " + value);
    }
  }

  private static Map<String, Object> resultBody(String result, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "service");
    body.put("name", name);
    return body;
  }
}
