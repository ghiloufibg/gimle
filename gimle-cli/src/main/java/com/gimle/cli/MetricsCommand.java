package com.gimle.cli;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/**
 * {@code metrics} -- the control plane's per-deployment request/error-rate rollup ({@code GET
 * /metrics}): one row per deployment carrying its owning tenant, its average request rate, its
 * average error rate, and how many instances actually contributed a reading.
 *
 * <p>The response spans every tenant the caller may read, so two tenants each running a deployment
 * of the same name produce two rows. Each carries its own {@code tenantId}, which is what tells
 * them apart -- rows are printed exactly as the server sent them, never merged (that would invent
 * an average across tenants the server never computed) and never dropped.
 */
public final class MetricsCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public MetricsCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void run(List<String> args) {
    if (!args.isEmpty()) {
      throw new CliException("unexpected argument: " + args.get(0) + "\n\n" + usage());
    }
    List<Map<String, Object>> rows = client.getList("/metrics");
    OutputFormat.printList(output, rows, out);
  }

  static String usage() {
    return "usage: gimle metrics";
  }
}
