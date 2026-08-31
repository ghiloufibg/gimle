package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code get alertrules [name]}, {@code set alertrule <name> --deployment <name> --metric
 * <REQUEST_RATE_PER_SECOND|ERROR_RATE_PER_SECOND|QUEUE_DEPTH|CPU_MILLICORES_USED|
 * MEMORY_BYTES_USED> --comparator <GREATER_THAN|LESS_THAN> --threshold <n> --webhook <url>
 * [--tenant <id>] [--disabled]}, {@code delete alertrule <name>} -- the alerting primitive's CLI
 * surface. {@code set} POSTs to the bare {@code /alertrules} collection rather than PUTting {@code
 * /alertrules/<name>}, matching {@code ApiServer}'s own routing: an {@link
 * com.gimle.mimir.manifest.AlertRuleSpec} names itself in the request body, the same convention
 * {@link ServicesCommand} already establishes for the sibling network-model resource.
 */
public final class AlertRulesCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public AlertRulesCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void get(List<String> args) {
    if (args.isEmpty()) {
      OutputFormat.printList(output, client.getList("/alertrules"), out);
      return;
    }
    String name = args.get(0);
    String path = TenantQuery.appendTo("/alertrules/" + name, args.subList(1, args.size()));
    OutputFormat.printObject(output, client.getObject(path), out);
  }

  public void set(List<String> args) {
    String usage =
        "set alertrule requires <name> --deployment <name> --metric"
            + " <REQUEST_RATE_PER_SECOND|ERROR_RATE_PER_SECOND|QUEUE_DEPTH|CPU_MILLICORES_USED|"
            + "MEMORY_BYTES_USED> --comparator <GREATER_THAN|LESS_THAN> --threshold <n> --webhook"
            + " <url> [--tenant <id>] [--disabled]";
    if (args.isEmpty()) {
      throw new CliException(usage);
    }
    String name = args.get(0);
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of("--disabled"), Set.of(), usage);
    String deploymentName = flags.getOrDefault("--deployment", null);
    String metric = flags.getOrDefault("--metric", null);
    String comparator = flags.getOrDefault("--comparator", null);
    String thresholdValue = flags.getOrDefault("--threshold", null);
    String webhookUrl = flags.getOrDefault("--webhook", null);
    if (deploymentName == null
        || metric == null
        || comparator == null
        || thresholdValue == null
        || webhookUrl == null) {
      throw new CliException(usage);
    }
    double threshold = parseThreshold(thresholdValue);
    String tenantId = flags.getOrDefault("--tenant", null);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    if (tenantId != null) {
      body.put("tenantId", tenantId);
    }
    body.put("deploymentName", deploymentName);
    body.put("metric", metric);
    body.put("comparator", comparator);
    body.put("threshold", threshold);
    body.put("webhookUrl", webhookUrl);
    body.put("enabled", !flags.isSet("--disabled"));

    client.expectSuccess(client.post("/alertrules", Json.write(body)));
    OutputFormat.printResult(
        output, resultBody("configured", name), "alertrule/" + name + " configured", out);
  }

  public void delete(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("missing alert rule name/id");
    }
    String name = args.get(0);
    String path = TenantQuery.appendTo("/alertrules/" + name, args.subList(1, args.size()));
    client.expectSuccess(client.delete(path));
    OutputFormat.printResult(
        output, resultBody("deleted", name), "alertrule/" + name + " deleted", out);
  }

  private static double parseThreshold(String value) {
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw new CliException("--threshold must be a number: " + value);
    }
  }

  private static Map<String, Object> resultBody(String result, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("kind", "alertrule");
    body.put("name", name);
    return body;
  }
}
