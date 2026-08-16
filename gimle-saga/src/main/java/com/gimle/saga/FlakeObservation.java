package com.gimle.saga;

import com.gimle.core.protocol.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One flake seen in one run: a test attempt that failed and whose immediately-following attempt
 * passed. Stored as one JSON line in the derived {@code index/flake-ledger.ndjson}; {@code
 * failureSignature} is the failed attempt's signature ({@code ""} when the shipper provided none),
 * so correlated flakes cluster across runs.
 */
public record FlakeObservation(
    String testId, String runId, long dateEpochMilli, String failureSignature) {

  String toJsonLine() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("testId", testId);
    json.put("runId", runId);
    json.put("dateEpochMilli", dateEpochMilli);
    json.put("failureSignature", failureSignature);
    return Json.write(json);
  }

  static FlakeObservation fromJsonLine(String line) {
    Map<String, Object> json = Json.asObject(Json.parse(line));
    return new FlakeObservation(
        String.valueOf(json.get("testId")),
        String.valueOf(json.get("runId")),
        json.get("dateEpochMilli") instanceof Number n ? n.longValue() : 0L,
        String.valueOf(json.getOrDefault("failureSignature", "")));
  }
}
