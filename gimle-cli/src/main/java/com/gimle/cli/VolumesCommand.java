package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code volume list} and {@code volume destroy <statefulSet> <index> --node <nodeId>} -- the
 * operator surface over StatefulSet persistent volumes, including retained orphans a permanent
 * removal left behind under the default {@code Retain} reclaim policy. {@code destroy} is the
 * explicit, irreversible reclaim of one of those orphans: the control plane refuses it while the
 * volume is still attached, so there is no {@code --force} to reach for -- an attached volume is
 * scaled down or its spec deleted first, never destroyed out from under a live instance.
 */
public final class VolumesCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public VolumesCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void run(List<String> args) {
    if (args.isEmpty()) {
      throw new CliException(usage());
    }
    switch (args.get(0)) {
      case "list" -> list();
      case "destroy" -> destroy(args.subList(1, args.size()));
      default -> throw new CliException(usage());
    }
  }

  private void list() {
    Map<String, Object> body = client.getObject("/volumes");
    Object rawVolumes = body.get("volumes");
    List<Map<String, Object>> volumes =
        rawVolumes == null ? List.of() : Json.asObjectList(rawVolumes);
    OutputFormat.printList(output, volumes, out);
    Object unreachable = body.get("unreachableNodes");
    if (unreachable != null) {
      out.println("warning: unreachable nodes not included in this listing: " + unreachable);
    }
  }

  private void destroy(List<String> args) {
    if (args.size() < 2) {
      throw new CliException(usage());
    }
    String statefulSet = args.get(0);
    String index = args.get(1);
    if (!index.chars().allMatch(Character::isDigit)) {
      throw new CliException("instance index must be a number: " + index);
    }
    Flags flags = Flags.parse(args.subList(2, args.size()), Set.of());
    String nodeId = flags.getOrDefault("--node", null);
    if (nodeId == null || nodeId.isBlank()) {
      throw new CliException("--node <nodeId> is required for volume destroy");
    }
    ApiResponse response = client.delete("/volumes/" + nodeId + "/" + statefulSet + "/" + index);
    client.expectSuccess(response);
    out.println("destroyed volume " + statefulSet + "[" + index + "] on node " + nodeId);
  }

  private static String usage() {
    return """
        usage:
          gimle volume list
          gimle volume destroy <statefulSet> <instanceIndex> --node <nodeId>""";
  }
}
