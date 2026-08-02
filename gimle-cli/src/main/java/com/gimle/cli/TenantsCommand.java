package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code get tenants [id]}, {@code set tenant <id> --max-memory-bytes N --max-cpu-millicores N
 * --max-instances N}, {@code delete tenant <id>}.
 */
public final class TenantsCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public TenantsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    if (args.isEmpty()) {
      OutputFormat.printList(output, client.getList("/tenants"), out);
      return;
    }
    String id = args.get(0);
    OutputFormat.printObject(output, client.getObject("/tenants/" + id), out);
  }

  public void set(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("set tenant requires <id>");
    }
    String id = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of());
    long maxMemoryBytes = flags.requireLong("--max-memory-bytes");
    long maxCpuMillicores = flags.requireLong("--max-cpu-millicores");
    long maxInstances = flags.requireLong("--max-instances");

    Map<String, Object> quota = new LinkedHashMap<>();
    quota.put("maxMemoryBytes", maxMemoryBytes);
    quota.put("maxCpuMillicores", maxCpuMillicores);
    quota.put("maxInstances", maxInstances);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("quota", quota);

    client.expectSuccess(client.put("/tenants/" + id, Json.write(body)));
    OutputFormat.printResult(
        output, resultBody("configured", id), "tenant/" + id + " configured", out);
  }

  public void delete(String id) {
    client.expectSuccess(client.delete("/tenants/" + id));
    OutputFormat.printResult(output, resultBody("deleted", id), "tenant/" + id + " deleted", out);
  }

  private static Map<String, Object> resultBody(String result, String id) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "tenant");
    body.put("id", id);
    return body;
  }
}
