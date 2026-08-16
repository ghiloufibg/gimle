package com.gimle.hilmir.release;

import com.gimle.core.protocol.Json;
import com.gimle.hilmir.HilmirException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code hilmir release-status <name> [-o json]}: the release's {@code .meta} row plus each listed
 * resource's live status.
 */
public final class ReleaseStatusCommand {

  private ReleaseStatusCommand() {}

  public static int run(List<String> args, PrintStream out) {
    if (args.isEmpty() || args.get(0).startsWith("-")) {
      throw new HilmirException("release-status requires a release name");
    }
    String releaseName = args.get(0);
    ReleaseFlags flags = ReleaseFlags.parse(args.subList(1, args.size()), Set.of(), Set.of());
    boolean json = ReleaseOutput.isJson(flags);
    ControlPlaneApi api = new ControlPlaneApi(ReleaseServer.resolve(flags));

    ReleaseMeta meta =
        ReleaseLedger.readMeta(api, releaseName)
            .orElseThrow(() -> new HilmirException("no release named '" + releaseName + "'"));
    ReleaseRevision current =
        ReleaseLedger.readRevision(api, releaseName, meta.currentRevision())
            .orElseThrow(
                () ->
                    new HilmirException(
                        "release '"
                            + releaseName
                            + "' has no revision "
                            + meta.currentRevision()
                            + " recorded in its ledger"));

    List<Map<String, Object>> resourceStatuses = new ArrayList<>();
    for (ResourceRef resource : current.resources()) {
      Map<String, Object> status = new LinkedHashMap<>();
      status.put("kind", resource.kind());
      status.put("name", resource.name());
      try {
        status.put(
            "status", api.getObject(WorkloadKinds.pathPrefix(resource.kind()) + resource.name()));
      } catch (HilmirException e) {
        status.put("status", Map.of("error", e.getMessage()));
      }
      resourceStatuses.add(status);
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", releaseName);
    body.put("bundleVersion", meta.bundleVersion());
    body.put("currentRevision", meta.currentRevision());
    body.put("tenants", meta.tenants());
    body.put("resources", resourceStatuses);

    if (json) {
      out.println(Json.write(body));
      return 0;
    }
    out.println("release: " + releaseName);
    out.println("  bundleVersion: " + meta.bundleVersion());
    out.println("  currentRevision: " + meta.currentRevision());
    out.println("  tenants: " + meta.tenants());
    for (Map<String, Object> resourceStatus : resourceStatuses) {
      out.println(
          "  "
              + resourceStatus.get("kind")
              + "/"
              + resourceStatus.get("name")
              + ": "
              + resourceStatus.get("status"));
    }
    return 0;
  }
}
