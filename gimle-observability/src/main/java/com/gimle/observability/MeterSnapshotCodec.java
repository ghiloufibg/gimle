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
 * The pure (no-I/O) meter-to-NDJSON-line serialization shared by every metrics shipping path:
 * {@link MuninnShipper#startShippingMetrics} ships a snapshot straight from a local {@code
 * MeterRegistry} over HTTP; a worker JVM (design doc §6c) has no outbound network identity of its
 * own, so it builds the same NDJSON shape here and hands it to its agent over the control channel
 * for relay instead. Extracted out of {@code MuninnShipper} so both callers produce byte-identical
 * lines rather than maintaining two serializations of the same meter shape.
 */
public final class MeterSnapshotCodec {

  private MeterSnapshotCodec() {}

  /** One NDJSON line per meter currently in {@code registry}, newline-terminated. */
  public static String toNdjson(MeterRegistry registry) {
    StringBuilder body = new StringBuilder();
    for (Meter meter : registry.getMeters()) {
      body.append(Json.write(toLine(meter))).append('\n');
    }
    return body.toString();
  }

  static Map<String, Object> toLine(Meter meter) {
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
