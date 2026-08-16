package com.gimle.saga;

import com.gimle.core.protocol.Json;
import com.gimle.core.saga.SagaEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The derived, rewritable summary of one run, stored beside its append-only {@code events.ndjson}
 * as {@code meta.json}. Every field is a pure fold over the run's own event stream, so a missing or
 * stale {@code meta.json} can always be reconstructed by replaying the events -- the events file is
 * the authority, this is the index entry.
 */
public record RunMeta(
    String runId,
    long startedAtEpochMilli,
    long finishedAtEpochMilli,
    String gitBranch,
    String gitSha,
    String command,
    List<String> modules,
    RunStatus status,
    Optional<SagaEvent.RunTotals> totals) {

  public RunMeta {
    modules = List.copyOf(modules);
  }

  /**
   * A run's consumer-visible state: {@code LIVE} while events are still arriving, {@code PASSED}/
   * {@code FAILED} once a run-finished event lands, and {@code ABANDONED} for a run that was still
   * live when the server restarted -- its writer is gone, so no run-finished event can ever arrive.
   */
  public enum RunStatus {
    LIVE,
    PASSED,
    FAILED,
    ABANDONED;

    String wireName() {
      return name().toLowerCase(Locale.ROOT);
    }

    static RunStatus fromWireName(String name) {
      return valueOf(name.toUpperCase(Locale.ROOT));
    }
  }

  static RunMeta emptyFor(String runId) {
    return new RunMeta(runId, 0L, 0L, "", "", "", List.of(), RunStatus.LIVE, Optional.empty());
  }

  RunMeta fold(SagaEvent event) {
    return switch (event) {
      case SagaEvent.RunStarted e ->
          new RunMeta(
              runId,
              e.startedAtEpochMilli(),
              finishedAtEpochMilli,
              e.gitBranch(),
              e.gitSha(),
              e.command(),
              e.modules(),
              status,
              totals);
      case SagaEvent.RunFinished e ->
          new RunMeta(
              runId,
              startedAtEpochMilli,
              e.finishedAtEpochMilli(),
              gitBranch,
              gitSha,
              command,
              modules,
              e.totals().failed() > 0 ? RunStatus.FAILED : RunStatus.PASSED,
              Optional.of(e.totals()));
      default -> this;
    };
  }

  RunMeta abandoned() {
    return new RunMeta(
        runId,
        startedAtEpochMilli,
        finishedAtEpochMilli,
        gitBranch,
        gitSha,
        command,
        modules,
        RunStatus.ABANDONED,
        totals);
  }

  public Map<String, Object> toJsonMap() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("runId", runId);
    json.put("startedAtEpochMilli", startedAtEpochMilli);
    json.put("finishedAtEpochMilli", finishedAtEpochMilli);
    json.put("gitBranch", gitBranch);
    json.put("gitSha", gitSha);
    json.put("command", command);
    json.put("modules", modules);
    json.put("status", status.wireName());
    totals.ifPresent(
        t -> {
          Map<String, Object> totalsJson = new LinkedHashMap<>();
          totalsJson.put("tests", t.tests());
          totalsJson.put("passed", t.passed());
          totalsJson.put("failed", t.failed());
          totalsJson.put("skipped", t.skipped());
          totalsJson.put("flaky", t.flaky());
          json.put("totals", totalsJson);
        });
    return json;
  }

  static RunMeta fromJsonMap(Map<String, Object> json) {
    Optional<SagaEvent.RunTotals> totals = Optional.empty();
    if (json.get("totals") instanceof Map<?, ?>) {
      Map<String, Object> t = Json.asObject(json.get("totals"));
      totals =
          Optional.of(
              new SagaEvent.RunTotals(
                  intOf(t, "tests"),
                  intOf(t, "passed"),
                  intOf(t, "failed"),
                  intOf(t, "skipped"),
                  intOf(t, "flaky")));
    }
    List<String> modules =
        Json.asArray(json.getOrDefault("modules", List.of())).stream()
            .map(String::valueOf)
            .toList();
    return new RunMeta(
        String.valueOf(json.get("runId")),
        longOf(json, "startedAtEpochMilli"),
        longOf(json, "finishedAtEpochMilli"),
        String.valueOf(json.getOrDefault("gitBranch", "")),
        String.valueOf(json.getOrDefault("gitSha", "")),
        String.valueOf(json.getOrDefault("command", "")),
        modules,
        RunStatus.fromWireName(String.valueOf(json.get("status"))),
        totals);
  }

  private static long longOf(Map<String, Object> json, String key) {
    return json.get(key) instanceof Number n ? n.longValue() : 0L;
  }

  private static int intOf(Map<String, Object> json, String key) {
    return json.get(key) instanceof Number n ? n.intValue() : 0;
  }
}
