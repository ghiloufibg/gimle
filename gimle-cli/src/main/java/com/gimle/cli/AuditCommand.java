package com.gimle.cli;

import java.io.PrintStream;
import java.util.List;
import java.util.Set;

/**
 * {@code audit list [--principal <name>] [--resource <kind>] [--tenant <id>] [--since <ts>]
 * [--limit N]} -- reads the cross-resource audit trail: every access-control decision the control
 * plane records (role/permission changes, secret access, resource writes, and any reads opted into
 * auditing), independent of any single resource kind's own history, so an operator can answer "who
 * did what" without knowing in advance which resource to look under. Every filter is optional and
 * independently combinable, matching {@code ApiServer}'s own {@code GET /audit} query-parameter
 * shape exactly.
 */
public final class AuditCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public AuditCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void run(List<String> args) {
    if (args.isEmpty() || !"list".equals(args.get(0))) {
      throw new CliException(usage());
    }
    Flags flags = Flags.parse(args.subList(1, args.size()), Set.of(), usage());

    StringBuilder path = new StringBuilder("/audit");
    char separator = '?';
    separator = appendFilter(path, separator, "principal", flags.getOrDefault("--principal", null));
    separator = appendFilter(path, separator, "resource", flags.getOrDefault("--resource", null));
    separator = appendFilter(path, separator, "tenant", flags.getOrDefault("--tenant", null));
    separator = appendFilter(path, separator, "since", flags.getOrDefault("--since", null));
    appendFilter(path, separator, "limit", flags.getOrDefault("--limit", null));

    OutputFormat.printList(output, client.getList(path.toString()), out);
  }

  private static char appendFilter(StringBuilder path, char separator, String name, String value) {
    if (value == null) {
      return separator;
    }
    path.append(separator).append(name).append('=').append(value);
    return '&';
  }

  static String usage() {
    return """
        usage: gimle audit list [--principal <name>] [--resource <kind>] [--tenant <id>]
                                 [--since <epochMillis>] [--limit N]""";
  }
}
