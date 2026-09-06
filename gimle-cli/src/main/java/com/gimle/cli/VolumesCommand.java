package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code volume list} and {@code volume destroy <statefulSet> <index> --node <nodeId> [--tenant
 * <id>]} -- the operator surface over StatefulSet persistent volumes, including retained orphans a
 * permanent removal left behind under the default {@code Retain} reclaim policy. {@code destroy} is
 * the explicit, irreversible reclaim of one of those orphans: the control plane refuses it while
 * the volume is still attached, so there is no {@code --force} to reach for -- an attached volume
 * is scaled down or its spec deleted first, never destroyed out from under a live instance.
 */
public final class VolumesCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;
  private final PrintStream err;

  public VolumesCommand(
      ControlPlaneClient client, OutputFormat.Kind output, PrintStream out, PrintStream err) {
    this.client = client;
    this.output = output;
    this.out = out;
    this.err = err;
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
      // Diagnostic, not result data: on stderr so it neither corrupts the JSON array on stdout
      // under -o json nor lands in a table an operator is piping onward, while still being
      // impossible to miss at a terminal.
      err.println("warning: unreachable nodes not included in this listing: " + unreachable);
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
    Flags flags = Flags.parse(args.subList(2, args.size()), Set.of(), usage());
    String nodeId = flags.getOrDefault("--node", null);
    if (nodeId == null || nodeId.isBlank()) {
      throw new CliException("--node <nodeId> is required for volume destroy\n\n" + usage());
    }
    // Omitted means the untenanted namespace, exactly as the API reads it and exactly as `volume
    // list` renders it (an empty tenantId cell). Sending nothing at all would leave the tenant for
    // the server to guess, which is how a destroy could land on a different tenant's volume.
    String tenant = flags.getOrDefault("--tenant", null);
    String tenantQuery =
        tenant == null || tenant.isBlank()
            ? ""
            : "?tenant=" + URLEncoder.encode(tenant, StandardCharsets.UTF_8);
    ApiResponse response =
        client.delete("/volumes/" + nodeId + "/" + statefulSet + "/" + index + tenantQuery);
    String coordinate =
        statefulSet
            + "["
            + index
            + "] on node "
            + nodeId
            + (tenant == null || tenant.isBlank() ? "" : " for tenant " + tenant);
    // A reclaim that found nothing must not exit the same way a real one does: an operator who
    // mistyped the node, the index, or the tenant would otherwise read success into a volume they
    // never touched, with only the printed sentence -- which a script never reads -- to say
    // otherwise. The node reports it as a 404, and this carries that all the way out to the
    // process's own exit status.
    if (response.statusCode() == 404) {
      throw CliException.notFound("no volume to destroy: " + coordinate);
    }
    client.expectSuccess(response);
    Map<String, Object> resultBody = new LinkedHashMap<>();
    resultBody.put("result", "destroyed");
    resultBody.put("kind", "volume");
    resultBody.put("id", statefulSet + "/" + index);
    resultBody.put("nodeId", nodeId);
    resultBody.put("tenantId", tenant == null || tenant.isBlank() ? null : tenant);
    OutputFormat.printResult(output, resultBody, "destroyed volume " + coordinate, out);
  }

  static String usage() {
    return """
        usage:
          gimle volume list
          gimle volume destroy <statefulSet> <instanceIndex> --node <nodeId> [--tenant <id>]

        --tenant is omitted for an untenanted volume, matching the empty tenantId `volume list`
        shows for one; a tenanted volume is only ever addressable by naming its own tenant.""";
  }
}
