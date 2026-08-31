package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.nio.file.Path;
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
    String id = GimleCli.requireAtMostOne(args, "tenant");
    if (id == null) {
      OutputFormat.printList(output, client.getList("/tenants"), out);
      return;
    }
    OutputFormat.printObject(output, client.getObject("/tenants/" + id), out);
  }

  public void set(List<String> args) {
    String usage =
        "set tenant requires <id> --max-memory-bytes N --max-cpu-millicores N --max-instances N";
    if (args.isEmpty()) {
      throw new CliException(usage);
    }
    String id = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of(), usage);
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

  /**
   * {@code apply -f <manifest.yaml>} for {@code kind: Tenant}. Uses {@code name:} for the
   * identifier, matching every other manifest kind's own top-level field, even though {@code
   * set}/{@code get}/{@code delete} above call it {@code id} -- the wire path segment is identical
   * either way.
   */
  public void apply(List<String> args, PrintStream err) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    Map<String, Object> root = ManifestFiles.parseRoot(file, manifestBytes);
    String id = requireString(root, "name", file);
    if (!(root.get("quota") instanceof Map<?, ?> quotaMap)) {
      throw new CliException("manifest " + file + " requires a top-level 'quota' block");
    }
    Object maxMemoryBytes = quotaMap.get("maxMemoryBytes");
    Object maxCpuMillicores = quotaMap.get("maxCpuMillicores");
    Object maxInstances = quotaMap.get("maxInstances");
    if (maxMemoryBytes == null || maxCpuMillicores == null || maxInstances == null) {
      throw new CliException(
          "manifest "
              + file
              + " quota requires maxMemoryBytes, maxCpuMillicores, and maxInstances");
    }

    Map<String, Object> quota = new LinkedHashMap<>();
    quota.put("maxMemoryBytes", maxMemoryBytes);
    quota.put("maxCpuMillicores", maxCpuMillicores);
    quota.put("maxInstances", maxInstances);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("quota", quota);

    ApiResponse response = client.put("/tenants/" + id, Json.write(body));
    client.expectSuccess(response);
    ManifestFiles.printWarnings(response, err);
    OutputFormat.printResult(output, resultBody("applied", id), "tenant/" + id + " applied", out);
  }

  private static String requireString(Map<String, Object> root, String field, Path file) {
    if (!(root.get(field) instanceof String value) || value.isBlank()) {
      throw new CliException("manifest " + file + " has no top-level '" + field + "' field");
    }
    return value;
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
