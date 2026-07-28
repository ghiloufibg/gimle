package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code get config <tenantId>}, {@code set config <tenantId> <key> <value> [--encrypted]}, {@code
 * delete config <tenantId> <key>}.
 */
public final class ConfigCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public ConfigCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void list(String tenantId) {
    OutputFormat.printList(output, client.getList("/config/" + tenantId), out);
  }

  public void set(List<String> args) {
    if (args.size() < 3) {
      throw new CliException("set config requires <tenantId> <key> <value>");
    }
    String tenantId = args.get(0);
    String key = args.get(1);
    String value = args.get(2);
    Flags flags = Flags.parse(args.subList(3, args.size()), Set.of("--encrypted"));
    boolean encrypted = flags.isSet("--encrypted");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("value", value);
    body.put("encrypted", encrypted);
    client.expectSuccess(client.put("/config/" + tenantId + "/" + key, Json.write(body)));
    out.println("config/" + tenantId + "/" + key + " set");
  }

  public void delete(List<String> args) {
    if (args.size() < 2) {
      throw new CliException("delete config requires <tenantId> <key>");
    }
    String tenantId = args.get(0);
    String key = args.get(1);
    client.expectSuccess(client.delete("/config/" + tenantId + "/" + key));
    out.println("config/" + tenantId + "/" + key + " deleted");
  }
}
