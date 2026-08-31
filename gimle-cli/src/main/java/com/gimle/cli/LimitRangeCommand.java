package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code get limitrange [tenantId]}, {@code set limitrange <tenantId> --min-request-memory M
 * --min-request-cpu M --max-request-memory M --max-request-cpu M --min-limit-memory M
 * --min-limit-cpu M --max-limit-memory M --max-limit-cpu M}, {@code delete limitrange <tenantId>}
 * -- every bound pair (memory + cpu together) is independently optional, mirroring {@link
 * TenantsCommand}'s own per-field-flag shape.
 */
public final class LimitRangeCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public LimitRangeCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    String tenantId = GimleCli.requireAtMostOne(args, "limitrange");
    if (tenantId == null) {
      OutputFormat.printList(output, client.getList("/limitranges"), out);
      return;
    }
    OutputFormat.printObject(output, client.getObject("/limitranges/" + tenantId), out);
  }

  public void set(List<String> args) {
    String usage =
        "set limitrange requires <tenantId> --min-request-memory M --min-request-cpu M"
            + " --max-request-memory M --max-request-cpu M --min-limit-memory M --min-limit-cpu M"
            + " --max-limit-memory M --max-limit-cpu M";
    if (args.isEmpty()) {
      throw new CliException(usage);
    }
    String tenantId = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of(), usage);

    Map<String, Object> body = new LinkedHashMap<>();
    putBoundIfPresent(body, flags, "minRequest", "--min-request-memory", "--min-request-cpu");
    putBoundIfPresent(body, flags, "maxRequest", "--max-request-memory", "--max-request-cpu");
    putBoundIfPresent(body, flags, "minLimit", "--min-limit-memory", "--min-limit-cpu");
    putBoundIfPresent(body, flags, "maxLimit", "--max-limit-memory", "--max-limit-cpu");

    client.expectSuccess(client.put("/limitranges/" + tenantId, Json.write(body)));
    OutputFormat.printResult(
        output, resultBody("configured", tenantId), "limitrange/" + tenantId + " configured", out);
  }

  /**
   * {@code apply -f <manifest.yaml>} for {@code kind: LimitRange}. Each bound is a nested {@code
   * {memory, cpu}} mapping under its own key ({@code minRequest}/{@code maxRequest}/{@code
   * minLimit}/{@code maxLimit}) rather than four flag pairs -- both fields are still required
   * together when the block is present, the same all-or-nothing rule {@link #set}'s own {@link
   * #putBoundIfPresent} enforces.
   */
  public void apply(List<String> args, PrintStream err) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    Map<String, Object> root = ManifestFiles.parseRoot(file, manifestBytes);
    String tenantId = requireString(root, "name", file);

    Map<String, Object> body = new LinkedHashMap<>();
    for (String key : List.of("minRequest", "maxRequest", "minLimit", "maxLimit")) {
      putBoundIfPresent(body, root, key, file);
    }

    ApiResponse response = client.put("/limitranges/" + tenantId, Json.write(body));
    client.expectSuccess(response);
    ManifestFiles.printWarnings(response, err);
    OutputFormat.printResult(
        output, resultBody("applied", tenantId), "limitrange/" + tenantId + " applied", out);
  }

  private static void putBoundIfPresent(
      Map<String, Object> body, Map<String, Object> root, String key, Path file) {
    if (!(root.get(key) instanceof Map<?, ?> bound)) {
      return;
    }
    Object memory = bound.get("memory");
    Object cpu = bound.get("cpu");
    if (memory == null || cpu == null) {
      throw new CliException(
          "manifest " + file + "'s '" + key + "' requires both 'memory' and 'cpu'");
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    copy.put("memory", memory);
    copy.put("cpu", cpu);
    body.put(key, copy);
  }

  private static String requireString(Map<String, Object> root, String field, Path file) {
    if (!(root.get(field) instanceof String value) || value.isBlank()) {
      throw new CliException("manifest " + file + " has no top-level '" + field + "' field");
    }
    return value;
  }

  public void delete(String tenantId) {
    client.expectSuccess(client.delete("/limitranges/" + tenantId));
    OutputFormat.printResult(
        output, resultBody("deleted", tenantId), "limitrange/" + tenantId + " deleted", out);
  }

  /**
   * Each of the four bound pairs is all-or-nothing: setting one flag without the other is a 400.
   */
  private static void putBoundIfPresent(
      Map<String, Object> body, Flags flags, String key, String memoryFlag, String cpuFlag) {
    String memory = flags.getOrDefault(memoryFlag, null);
    String cpu = flags.getOrDefault(cpuFlag, null);
    if (memory == null && cpu == null) {
      return;
    }
    if (memory == null) {
      throw new CliException(
          memoryFlag
              + " is required alongside "
              + cpuFlag
              + " (each limitrange bound is memory+cpu together)");
    }
    if (cpu == null) {
      throw new CliException(
          cpuFlag
              + " is required alongside "
              + memoryFlag
              + " (each limitrange bound is memory+cpu together)");
    }
    Map<String, Object> bound = new LinkedHashMap<>();
    bound.put("memory", memory);
    bound.put("cpu", cpu);
    body.put(key, bound);
  }

  private static Map<String, Object> resultBody(String result, String tenantId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "limitrange");
    body.put("id", tenantId);
    return body;
  }
}
