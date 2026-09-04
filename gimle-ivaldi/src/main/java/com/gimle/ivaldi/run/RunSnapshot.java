package com.gimle.ivaldi.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code GET /api/runs/current} (and {@code POST /api/runs}) response body: everything a client
 * needs to render a run's progress without re-reading its full log. {@code processes} is only ever
 * populated by a run that actually rebooted the cluster (see {@link RunController}) -- a
 * deploy-only run leaves the previous boot's process tree untouched and this controller has no
 * fresher list to report, so it stays empty rather than guessing.
 */
record RunSnapshot(
    String id,
    String clusterId,
    Optional<String> blueprintId,
    RunStatus status,
    boolean rebooted,
    List<ProcessInfo> processes,
    Optional<Integer> revision,
    Optional<String> error,
    String startedAt,
    String updatedAt) {

  record ProcessInfo(String role, String address, boolean ready) {
    Map<String, Object> toJsonMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("role", role);
      map.put("address", address);
      map.put("ready", ready);
      return map;
    }
  }

  static RunSnapshot idle() {
    return new RunSnapshot(
        null,
        null,
        Optional.empty(),
        RunStatus.IDLE,
        false,
        List.of(),
        Optional.empty(),
        Optional.empty(),
        "",
        "");
  }

  Map<String, Object> toJsonMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", id);
    map.put("clusterId", clusterId);
    blueprintId.ifPresent(v -> map.put("blueprintId", v));
    map.put("status", status.wireValue());
    map.put("rebooted", rebooted);
    map.put("processes", processes.stream().map(ProcessInfo::toJsonMap).toList());
    revision.ifPresent(v -> map.put("revision", v));
    map.put("error", error.orElse(null));
    map.put("startedAt", startedAt);
    map.put("updatedAt", updatedAt);
    return map;
  }
}
