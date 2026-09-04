package com.gimle.hugin.model;

import com.gimle.cli.spi.ClusterReader;
import com.gimle.core.protocol.Json;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A worker JVM's own shipped meter history, read through the control plane's {@code GET
 * /metrics-history/WORKER/{nodeId}:{workerId}} proxy onto Muninn. A worker has no listening address
 * of its own, so its history is filed under its node's id joined to its worker id.
 *
 * <p>The response is the same envelope the log routes serve -- {@code lines} plus paging cursors --
 * each line one meter as it stood at one snapshot. With no cursor asked for, the envelope already
 * holds the most recent page, which is exactly the window a sparkline wants.
 *
 * <p>Shipping to Muninn is optional: a cluster whose processes ship nowhere has no history at all,
 * and the proxy answers 404 for it. So every failure here, and every response that parses to
 * nothing, is an empty history rather than a reported error -- the pane draws no sparklines at all
 * rather than an empty chart claiming a reading of zero.
 */
public final class MetricsHistoryReader {

  /**
   * Which measurement carries the reading, in the order they are looked for. A gauge publishes
   * {@code VALUE}, a counter {@code COUNT}; the two are disjoint, so first-match is unambiguous.
   */
  private static final List<String> STATISTICS = List.of("VALUE", "COUNT", "TOTAL");

  private final ClusterReader reader;

  public MetricsHistoryReader(final ClusterReader reader) {
    this.reader = reader;
  }

  /**
   * {@code moduleCoordinate} narrows the read to one module's own meters: a Tier 1 worker JVM hosts
   * several modules and ships all of their meters under the one worker id, so without it a
   * density-packed instance would draw its neighbours' traffic as its own.
   */
  public MetricsHistory read(
      final String nodeId, final String workerId, final Optional<String> moduleCoordinate) {
    try {
      Map<String, Object> envelope = reader.getObject(path(nodeId, workerId));
      Object lines = envelope.get("lines");
      return lines instanceof List<?> list ? fold(list, moduleCoordinate) : MetricsHistory.EMPTY;
    } catch (RuntimeException e) {
      return MetricsHistory.EMPTY;
    }
  }

  static String path(final String nodeId, final String workerId) {
    return "/metrics-history/WORKER/"
        + URLEncoder.encode(nodeId + ":" + workerId, StandardCharsets.UTF_8);
  }

  /**
   * Folds shipped meter lines into one time-ordered series per meter name. Every field is read
   * defensively and a line this build cannot use is dropped on its own, never taking the rest of
   * the page with it.
   *
   * <p>Readings sharing a name and a timestamp are summed. After the module filter that is a single
   * tag set and so a no-op; without one it is the honest aggregate across whatever tag sets a meter
   * carries.
   */
  static MetricsHistory fold(final List<?> lines, final Optional<String> moduleCoordinate) {
    Map<String, TreeMap<Instant, Double>> readings = new LinkedHashMap<>();
    for (Object entry : lines) {
      if (!(entry instanceof Map<?, ?>)) {
        continue;
      }
      Map<String, Object> line = Json.asObject(entry);
      String name = string(line.get("name"));
      Optional<Instant> at = instant(line.get("timestamp"));
      Optional<Double> value = reading(line.get("measurements"));
      if (name.isBlank()
          || at.isEmpty()
          || value.isEmpty()
          || !belongsToModule(line.get("tags"), moduleCoordinate)) {
        continue;
      }
      readings
          .computeIfAbsent(name, key -> new TreeMap<>())
          .merge(at.orElseThrow(), value.orElseThrow(), Double::sum);
    }

    Map<String, MetricSeries> series = new LinkedHashMap<>();
    readings.forEach(
        (name, samples) ->
            series.put(
                name,
                new MetricSeries(
                    name,
                    samples.entrySet().stream()
                        .map(sample -> new MetricSample(sample.getKey(), sample.getValue()))
                        .toList())));
    return new MetricsHistory(series);
  }

  /**
   * A meter tagged with a module belongs to that module alone. One tagged with none -- the fabric
   * client counters are tagged by the interface they dialed, not by a caller -- belongs to the
   * worker as a whole and is kept for whichever instance is asking.
   */
  private static boolean belongsToModule(
      final Object rawTags, final Optional<String> moduleCoordinate) {
    if (moduleCoordinate.isEmpty() || !(rawTags instanceof Map<?, ?> tags)) {
      return true;
    }
    Object module = tags.get("module");
    return module == null
        || moduleCoordinate.orElseThrow().equals(module + "@" + tags.get("version"));
  }

  private static Optional<Double> reading(final Object rawMeasurements) {
    if (!(rawMeasurements instanceof Map<?, ?> measurements)) {
      return Optional.empty();
    }
    for (String statistic : STATISTICS) {
      if (measurements.get(statistic) instanceof Number number
          && Double.isFinite(number.doubleValue())) {
        return Optional.of(number.doubleValue());
      }
    }
    return Optional.empty();
  }

  private static Optional<Instant> instant(final Object value) {
    if (!(value instanceof String text) || text.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(text));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  private static String string(final Object value) {
    return value instanceof String text ? text : "";
  }
}
