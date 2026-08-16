package com.gimle.core.saga;

import com.gimle.core.exception.GimleCodecException;
import com.gimle.core.protocol.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Encodes/decodes a {@link SagaEvent} as one self-describing JSON line ({@code "type"}
 * discriminator first), the unit both shipped over HTTP ingest and stored in a run's {@code
 * events.ndjson}. Same hand-rolled posture as {@link Json} and {@code ControlMessageCodec}: the
 * schema is fixed and platform-owned, so no mapping library is warranted. Absent {@link Optional}
 * fields are omitted from the JSON entirely rather than written as null, keeping stored lines
 * minimal and diff-friendly.
 */
public final class SagaEventCodec {

  private SagaEventCodec() {}

  public static String encode(SagaEvent event) {
    Map<String, Object> json = new LinkedHashMap<>();
    switch (event) {
      case SagaEvent.RunStarted e -> {
        json.put("type", "run-started");
        json.put("runId", e.runId());
        json.put("startedAtEpochMilli", e.startedAtEpochMilli());
        json.put("gitBranch", e.gitBranch());
        json.put("gitSha", e.gitSha());
        json.put("command", e.command());
        json.put("modules", e.modules());
      }
      case SagaEvent.TestStarted e -> {
        json.put("type", "test-started");
        json.put("runId", e.runId());
        json.put("testId", e.testId());
        json.put("tags", e.tags());
        json.put("attempt", e.attempt());
      }
      case SagaEvent.TestFinished e -> {
        json.put("type", "test-finished");
        json.put("runId", e.runId());
        json.put("testId", e.testId());
        json.put("attempt", e.attempt());
        json.put("outcome", e.outcome().name());
        json.put("durationMillis", e.durationMillis());
        e.failureType().ifPresent(v -> json.put("failureType", v));
        e.failureMessage().ifPresent(v -> json.put("failureMessage", v));
        e.failureSignature().ifPresent(v -> json.put("failureSignature", v));
      }
      case SagaEvent.Attachment e -> {
        json.put("type", "attachment");
        json.put("runId", e.runId());
        json.put("kind", e.kind());
        json.put("payloadJson", e.payloadJson());
      }
      case SagaEvent.RunFinished e -> {
        json.put("type", "run-finished");
        json.put("runId", e.runId());
        json.put("finishedAtEpochMilli", e.finishedAtEpochMilli());
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("tests", e.totals().tests());
        totals.put("passed", e.totals().passed());
        totals.put("failed", e.totals().failed());
        totals.put("skipped", e.totals().skipped());
        totals.put("flaky", e.totals().flaky());
        json.put("totals", totals);
      }
    }
    return Json.write(json);
  }

  public static SagaEvent decode(String line) {
    Map<String, Object> json;
    try {
      json = Json.asObject(Json.parse(line));
    } catch (RuntimeException e) {
      throw GimleCodecException.malformedSagaEvent("not a JSON object: " + e.getMessage());
    }
    String type = stringField(json, "type");
    return switch (type) {
      case "run-started" ->
          new SagaEvent.RunStarted(
              stringField(json, "runId"),
              longField(json, "startedAtEpochMilli"),
              stringField(json, "gitBranch"),
              stringField(json, "gitSha"),
              stringField(json, "command"),
              stringListField(json, "modules"));
      case "test-started" ->
          new SagaEvent.TestStarted(
              stringField(json, "runId"),
              stringField(json, "testId"),
              stringListField(json, "tags"),
              intField(json, "attempt"));
      case "test-finished" ->
          new SagaEvent.TestFinished(
              stringField(json, "runId"),
              stringField(json, "testId"),
              intField(json, "attempt"),
              outcomeField(json),
              longField(json, "durationMillis"),
              optionalStringField(json, "failureType"),
              optionalStringField(json, "failureMessage"),
              optionalStringField(json, "failureSignature"));
      case "attachment" ->
          new SagaEvent.Attachment(
              stringField(json, "runId"),
              stringField(json, "kind"),
              stringField(json, "payloadJson"));
      case "run-finished" ->
          new SagaEvent.RunFinished(
              stringField(json, "runId"),
              longField(json, "finishedAtEpochMilli"),
              totalsField(json));
      default -> throw GimleCodecException.malformedSagaEvent("unknown event type: " + type);
    };
  }

  private static String stringField(Map<String, Object> json, String key) {
    if (json.get(key) instanceof String value) {
      return value;
    }
    throw GimleCodecException.malformedSagaEvent("missing or non-string field: " + key);
  }

  private static Optional<String> optionalStringField(Map<String, Object> json, String key) {
    Object value = json.get(key);
    if (value == null) {
      return Optional.empty();
    }
    if (value instanceof String string) {
      return Optional.of(string);
    }
    throw GimleCodecException.malformedSagaEvent("non-string field: " + key);
  }

  private static long longField(Map<String, Object> json, String key) {
    if (json.get(key) instanceof Number value) {
      return value.longValue();
    }
    throw GimleCodecException.malformedSagaEvent("missing or non-numeric field: " + key);
  }

  private static int intField(Map<String, Object> json, String key) {
    return Math.toIntExact(longField(json, key));
  }

  private static List<String> stringListField(Map<String, Object> json, String key) {
    if (!(json.get(key) instanceof List<?> values)) {
      throw GimleCodecException.malformedSagaEvent("missing or non-array field: " + key);
    }
    List<String> strings = new ArrayList<>(values.size());
    for (Object value : values) {
      if (!(value instanceof String string)) {
        throw GimleCodecException.malformedSagaEvent("non-string element in field: " + key);
      }
      strings.add(string);
    }
    return List.copyOf(strings);
  }

  private static SagaEvent.Outcome outcomeField(Map<String, Object> json) {
    String name = stringField(json, "outcome");
    try {
      return SagaEvent.Outcome.valueOf(name);
    } catch (IllegalArgumentException e) {
      throw GimleCodecException.malformedSagaEvent("unknown outcome: " + name);
    }
  }

  private static SagaEvent.RunTotals totalsField(Map<String, Object> json) {
    if (!(json.get("totals") instanceof Map<?, ?>)) {
      throw GimleCodecException.malformedSagaEvent("missing or non-object field: totals");
    }
    Map<String, Object> totals = Json.asObject(json.get("totals"));
    return new SagaEvent.RunTotals(
        intField(totals, "tests"),
        intField(totals, "passed"),
        intField(totals, "failed"),
        intField(totals, "skipped"),
        intField(totals, "flaky"));
  }
}
