package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code get services [name]}, {@code set service <name> (--deployment <name> [--deployment
 * <name>...] | --external-name <host>) --port <n> [--target-port <n>] [--tenant <id>]
 * [--session-affinity]}, {@code delete service <name>}, {@code service endpoints <name>} -- the
 * ClusterIP analogue's CLI surface. {@code set} POSTs to the bare {@code /services} collection
 * rather than PUTting {@code /services/<name>}, matching {@code ApiServer}'s own routing: a {@link
 * com.gimle.mimir.manifest.ServiceSpec} names itself in the request body, unlike every {@code
 * WorkloadSpec} kind's own {@code PUT /deployments/{name}}-shaped routes.
 */
public final class ServicesCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public ServicesCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    if (args.isEmpty()) {
      OutputFormat.printList(output, client.getList("/services"), out);
      return;
    }
    String name = args.get(0);
    OutputFormat.printObject(output, client.getObject("/services/" + name), out);
  }

  public void set(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("set service requires <name>");
    }
    String name = args.get(0);
    Flags flags =
        Flags.parse(
            args.subList(1, args.size()), Set.of("--session-affinity"), Set.of("--deployment"));
    List<String> deploymentNames = flags.getAll("--deployment");
    String externalName = flags.getOrDefault("--external-name", null);
    if (deploymentNames.isEmpty() && externalName == null) {
      throw new CliException(
          "set service requires at least one --deployment (or --external-name <host>)");
    }
    long port = flags.requireLong("--port");
    String targetPortValue = flags.getOrDefault("--target-port", null);
    long targetPort = targetPortValue == null ? port : parsePort(targetPortValue);
    String tenantId = flags.getOrDefault("--tenant", null);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    if (tenantId != null) {
      body.put("tenantId", tenantId);
    }
    body.put("deploymentNames", List.copyOf(new LinkedHashSet<>(deploymentNames)));
    body.put("port", port);
    body.put("targetPort", targetPort);
    if (flags.isSet("--session-affinity")) {
      body.put("sessionAffinity", true);
    }
    if (externalName != null) {
      body.put("externalName", externalName);
    }

    client.expectSuccess(client.post("/services", Json.write(body)));
    OutputFormat.printResult(
        output, resultBody("configured", name), "service/" + name + " configured", out);
  }

  public void delete(String name) {
    client.expectSuccess(client.delete("/services/" + name));
    OutputFormat.printResult(
        output, resultBody("deleted", name), "service/" + name + " deleted", out);
  }

  /** {@code GET /services/{name}/endpoints} -- the live, reconciler-independent endpoint set. */
  public void endpoints(String name) {
    OutputFormat.printObject(output, client.getObject("/services/" + name + "/endpoints"), out);
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
