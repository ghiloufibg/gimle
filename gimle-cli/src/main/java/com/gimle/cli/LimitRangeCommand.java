package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
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
    if (args.isEmpty()) {
      OutputFormat.printList(output, client.getList("/limitranges"), out);
      return;
    }
    String tenantId = args.get(0);
    OutputFormat.printObject(output, client.getObject("/limitranges/" + tenantId), out);
  }

  public void set(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("set limitrange requires <tenantId>");
    }
    String tenantId = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of());

    Map<String, Object> body = new LinkedHashMap<>();
    putBoundIfPresent(body, flags, "minRequest", "--min-request-memory", "--min-request-cpu");
    putBoundIfPresent(body, flags, "maxRequest", "--max-request-memory", "--max-request-cpu");
    putBoundIfPresent(body, flags, "minLimit", "--min-limit-memory", "--min-limit-cpu");
    putBoundIfPresent(body, flags, "maxLimit", "--max-limit-memory", "--max-limit-cpu");

    client.expectSuccess(client.put("/limitranges/" + tenantId, Json.write(body)));
    OutputFormat.printResult(
        output, resultBody("configured", tenantId), "limitrange/" + tenantId + " configured", out);
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
