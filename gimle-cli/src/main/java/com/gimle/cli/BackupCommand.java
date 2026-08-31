package com.gimle.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code backup create [--to <path>]}, {@code backup restore <path>} -- the operator-facing
 * counterpart to {@code StateStore#snapshot()}/{@code #restoreFromSnapshot}, which round-trip full
 * cluster state already but, until now, only ever internally (Raft catch-up/failover). Reaches
 * {@code gimle-controlplane}'s {@code /backup}/{@code /restore} proxy, never {@code gimle-mimir}
 * directly, the same routing decision every other tenant-facing command here already makes for its
 * own backing process.
 *
 * <p>The downloaded/uploaded bytes are {@code RaftCodec.encodeSnapshot}'s own already-versioned
 * encoding -- opaque to this class, never parsed here. {@code create} streams straight to a file
 * ({@link ControlPlaneClient#downloadFile}) and {@code restore} streams that same file straight
 * back ({@link ControlPlaneClient#putFile}), so a cluster's full state is never whole in this
 * process's own memory.
 */
public final class BackupCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public BackupCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void run(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException(usage());
    }
    String verb = args.get(0);
    List<String> rest = args.subList(1, args.size());
    switch (verb) {
      case "create" -> create(rest);
      case "restore" -> restore(rest);
      default -> throw new CliException(usage());
    }
  }

  private void create(List<String> args) {
    Flags flags = Flags.parse(args, Set.of(), "usage: gimle backup create [--to <path>]");
    Path target =
        Path.of(flags.getOrDefault("--to", "gimle-backup-" + Instant.now().getEpochSecond()));

    client.downloadFile("/backup", target);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", "created");
    body.put("kind", "backup");
    body.put("file", target.toString());
    OutputFormat.printResult(output, body, "cluster backup written to " + target, out);
  }

  private void restore(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException("backup restore requires <path>");
    }
    Path file = Path.of(args.get(0));

    client.expectSuccess(client.putFile("/restore", file));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", "restored");
    body.put("kind", "backup");
    body.put("file", file.toString());
    OutputFormat.printResult(output, body, "cluster state restored from " + file, out);
  }

  static String usage() {
    return """
        usage: gimle backup <verb> [args]

        verbs:
          create [--to <path>]
          restore <path>
        """;
  }
}
