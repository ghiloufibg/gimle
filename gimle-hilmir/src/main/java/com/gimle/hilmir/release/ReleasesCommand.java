package com.gimle.hilmir.release;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code hilmir releases [-o json]}: lists every release under the fixed {@code gimle-hilmir}
 * bookkeeping tenant.
 */
public final class ReleasesCommand {

  private ReleasesCommand() {}

  public static int run(List<String> args, PrintStream out) {
    ReleaseFlags flags = ReleaseFlags.parse(args, Set.of(), Set.of());
    boolean json = ReleaseOutput.isJson(flags);
    ControlPlaneApi api = new ControlPlaneApi(ReleaseServer.resolve(flags));

    List<Map<String, Object>> rows = new ArrayList<>();
    for (String name : ReleaseLedger.listReleaseNames(api)) {
      ReleaseLedger.readMeta(api, name)
          .ifPresent(
              meta -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("currentRevision", meta.currentRevision());
                row.put("bundleVersion", meta.bundleVersion());
                rows.add(row);
              });
    }

    if (json) {
      out.println(Json.write(rows));
      return 0;
    }
    if (rows.isEmpty()) {
      out.println("No releases found.");
      return 0;
    }
    out.println("NAME\tREVISION\tBUNDLE_VERSION");
    for (Map<String, Object> row : rows) {
      out.println(
          row.get("name") + "\t" + row.get("currentRevision") + "\t" + row.get("bundleVersion"));
    }
    return 0;
  }
}
