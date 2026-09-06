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

  /**
   * One process this run launched. {@code address} is what an operator can reach the process on,
   * which for a node agent is its declared UDP gossip address rather than anything connectable --
   * so the launcher's own {@code readinessAddress} ("" for a kind with no port-based signal) is
   * kept alongside it, and is what a readiness re-check actually probes. It is not serialized: a
   * client reads {@code ready}, never re-derives it. {@code machine} is the topology's own machine
   * name this process was placed on -- what lets a client group a multi-machine run's process list
   * by machine instead of showing one flat, unlabeled list.
   */
  record ProcessInfo(
      String role,
      String machine,
      String address,
      long pid,
      String readinessAddress,
      boolean ready) {

    ProcessInfo withReady(boolean value) {
      return new ProcessInfo(role, machine, address, pid, readinessAddress, value);
    }

    Map<String, Object> toJsonMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("role", role);
      map.put("machine", machine);
      map.put("address", address);
      map.put("pid", pid);
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
