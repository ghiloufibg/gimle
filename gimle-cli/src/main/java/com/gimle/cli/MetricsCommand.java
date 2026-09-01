package com.gimle.cli;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code metrics} -- the control plane's per-deployment request/error-rate rollup ({@code GET
 * /metrics}): one row per deployment carrying its average request rate, average error rate, and how
 * many instances actually contributed a reading.
 *
 * <p>The response keys each row by deployment name alone and carries no tenant id, while the
 * authorization filter behind it is per-tenant -- so a caller allowed to read two tenants that each
 * run a deployment of the same name receives two rows nothing in the payload distinguishes. Every
 * row is kept exactly as the server sent it and each side of such a collision is marked {@code
 * ambiguous}: merging them would invent an average across tenants the server never computed, and
 * dropping one would hide a real deployment. There is no client-side join available to do better --
 * nothing in the response says which tenant a row belongs to.
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
    Set<String> ambiguous = repeatedDeploymentNames(rows);
    List<Map<String, Object>> marked = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> copy = new LinkedHashMap<>(row);
      copy.put("ambiguous", ambiguous.contains(String.valueOf(row.get("deploymentName"))));
      marked.add(copy);
    }
    OutputFormat.printList(output, marked, out);
    // Only under the table format: -o json is meant to be piped into a JSON reader, and the
    // marker is already on every row there.
    if (output == OutputFormat.Kind.TABLE && !ambiguous.isEmpty()) {
      out.printf(
          "note: %s appear(s) more than once -- these rows carry no tenant id, so same-named"
              + " deployments in different tenants cannot be told apart here%n",
          String.join(", ", new TreeSet<>(ambiguous)));
    }
  }

  private static Set<String> repeatedDeploymentNames(List<Map<String, Object>> rows) {
    Set<String> seen = new LinkedHashSet<>();
    Set<String> repeated = new LinkedHashSet<>();
    for (Map<String, Object> row : rows) {
      String name = String.valueOf(row.get("deploymentName"));
      if (!seen.add(name)) {
        repeated.add(name);
      }
    }
    return repeated;
  }

  static String usage() {
    return "usage: gimle metrics";
  }
}
