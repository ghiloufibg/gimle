package com.gimle.hilmir.release;

import com.gimle.core.exception.GimleManifestException;
import java.util.Map;

/**
 * Maps a workload manifest's {@code kind:} field to the control plane's own URL path prefix for it.
 */
final class WorkloadKinds {

  private static final Map<String, String> PATH_PREFIXES =
      Map.of(
          "Deployment", "/deployments/",
          "Job", "/jobs/",
          "CronJob", "/cronjobs/",
          "DaemonSet", "/daemonsets/",
          "StatefulSet", "/statefulsets/");

  private WorkloadKinds() {}

  static String pathPrefix(String kind) {
    String prefix = PATH_PREFIXES.get(kind);
    if (prefix == null) {
      throw new GimleManifestException("unknown workload kind: " + kind);
    }
    return prefix;
  }
}
