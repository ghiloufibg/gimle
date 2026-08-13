package com.gimle.observability;

import com.gimle.core.protocol.Json;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Pure (no-I/O) NDJSON serialization for a {@link MeterRegistry} snapshot -- one JSON line per
 * meter, the exact shape {@link MuninnShipper#startShippingMetrics} has always shipped, extracted
 * here so a worker's own periodic snapshot ({@code WorkerMain}, relayed to Muninn through its agent
 * over the control channel, since workers have no outbound network identity of their own) produces
 * byte-identical output to a process shipping directly, without either path duplicating the
 * meter-to-JSON mapping.
 */
public final class MeterSnapshotCodec {

  private MeterSnapshotCodec() {}

  /** Empty string for a registry with no meters yet -- callers treat that as "nothing to ship". */
  public static String toNdjson(MeterRegistry registry) {
    return Json.writeNdjson(registry.getMeters(), MeterSnapshotCodec::meterToJsonLine);
  }

  private static Map<String, Object> meterToJsonLine(Meter meter) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("timestamp", Instant.now().toString());
    line.put("name", meter.getId().getName());
    line.put("type", meter.getId().getType().name());
    Map<String, Object> tags = new LinkedHashMap<>();
    for (Tag tag : meter.getId().getTags()) {
      tags.put(tag.getKey(), tag.getValue());
    }
    line.put("tags", tags);
    Map<String, Object> measurements = new LinkedHashMap<>();
    for (Measurement measurement : meter.measure()) {
      measurements.put(measurement.getStatistic().name(), measurement.getValue());
    }
    line.put("measurements", measurements);
    // Meter#measure()'s generic Statistic iteration above never yields percentiles -- those live on
    // Timer's own HistogramSnapshot, reachable only via takeSnapshot(). Scoped to Timer
    // specifically
    // (not the broader HistogramSupport interface DistributionSummary also implements): no
    // DistributionSummary meter exists in this codebase today, and ValueAtPercentile's
    // nanosecond-based value conversion is only meaningful for a time-based meter, matching the
    // seconds unit TOTAL_TIME/MAX already use above. Empty (the case for any Timer built without
    // publishPercentiles(...)) intentionally omits the key rather than shipping an empty map, so
    // every currently-shipped line is unchanged unless percentiles were explicitly configured.
    if (meter instanceof Timer timer) {
      HistogramSnapshot snapshot = timer.takeSnapshot();
      ValueAtPercentile[] percentileValues = snapshot.percentileValues();
      if (percentileValues.length > 0) {
        Map<String, Object> percentiles = new LinkedHashMap<>();
        for (ValueAtPercentile percentileValue : percentileValues) {
          percentiles.put(
              String.valueOf(percentileValue.percentile()),
              percentileValue.value(TimeUnit.SECONDS));
        }
        line.put("percentiles", percentiles);
      }
    }
    return line;
  }
}
