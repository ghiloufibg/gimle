package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.CliException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The shipped-meter history behind the drill-down's sparklines, parsed as Muninn serves it. */
class MetricsHistoryReaderTest {

  private static final String WORKER_PATH = "/metrics-history/WORKER/node-alpha%3Aworker-4471";
  private static final String COUNT_METER = "gimle.module.request.count";
  private static final String METASPACE_METER = "gimle.module.metaspace.bytes";
  private static final Optional<String> GREETER = Optional.of("greeter-consumer@1.0.0");

  @Test
  void a_meter_folds_into_one_series_per_name_in_time_order() {
    MetricsHistory history =
        read(
            List.of(
                counter(COUNT_METER, "2026-09-01T14:02:20Z", 200.0),
                gauge(METASPACE_METER, "2026-09-01T14:02:10Z", 12_582_912.0),
                counter(COUNT_METER, "2026-09-01T14:02:10Z", 140.0)));

    MetricSeries requests = history.series(COUNT_METER).orElseThrow();
    // Ordered by the reading's own timestamp, not by the order the page happened to list them.
    assertEquals(List.of(140.0, 200.0), requests.values());
    assertEquals(12_582_912.0, history.series(METASPACE_METER).orElseThrow().latest());
  }

  @Test
  void a_cumulative_counter_reads_back_as_a_per_second_rate() {
    MetricSeries requests =
        read(List.of(
                counter(COUNT_METER, "2026-09-01T14:02:10Z", 140.0),
                counter(COUNT_METER, "2026-09-01T14:02:20Z", 200.0),
                counter(COUNT_METER, "2026-09-01T14:02:30Z", 200.0)))
            .series(COUNT_METER)
            .orElseThrow();

    // Two rates out of three readings: 60 requests over ten seconds, then none at all.
    assertEquals(List.of(6.0, 0.0), requests.ratesPerSecond());
  }

  @Test
  void a_counter_that_restarted_with_its_worker_reads_as_no_traffic_rather_than_a_negative_rate() {
    MetricSeries requests =
        read(List.of(
                counter(COUNT_METER, "2026-09-01T14:02:10Z", 900.0),
                counter(COUNT_METER, "2026-09-01T14:02:20Z", 30.0)))
            .series(COUNT_METER)
            .orElseThrow();

    assertEquals(List.of(0.0), requests.ratesPerSecond());
  }

  @Test
  void a_malformed_line_is_dropped_on_its_own_and_the_rest_of_the_page_survives() {
    Map<String, Object> noTimestamp = counter(COUNT_METER, "2026-09-01T14:02:10Z", 10.0);
    noTimestamp.remove("timestamp");
    Map<String, Object> unparseableTimestamp = counter(COUNT_METER, "the day before", 20.0);
    Map<String, Object> noName = counter(COUNT_METER, "2026-09-01T14:02:12Z", 30.0);
    noName.remove("name");
    Map<String, Object> noMeasurements = counter(COUNT_METER, "2026-09-01T14:02:13Z", 40.0);
    noMeasurements.put("measurements", "not an object");

    List<Object> page = new ArrayList<>();
    page.add(noTimestamp);
    page.add(unparseableTimestamp);
    page.add(noName);
    page.add(noMeasurements);
    page.add("a bare string where an object should be");
    page.add(counter(COUNT_METER, "2026-09-01T14:02:20Z", 200.0));

    MetricsHistory history = MetricsHistoryReader.fold(page, GREETER);

    assertEquals(List.of(200.0), history.series(COUNT_METER).orElseThrow().values());
  }

  @Test
  void a_shared_workers_other_modules_meters_stay_out_of_this_instances_series() {
    // One Tier 1 worker JVM hosts both modules and ships both sets of meters under its own id.
    Map<String, Object> neighbour = counter(COUNT_METER, "2026-09-01T14:02:10Z", 50_000.0);
    neighbour.put("tags", Map.of("module", "billing", "version", "2.1.0"));

    MetricsHistory history =
        MetricsHistoryReader.fold(
            List.of(neighbour, counter(COUNT_METER, "2026-09-01T14:02:20Z", 200.0)), GREETER);

    assertEquals(List.of(200.0), history.series(COUNT_METER).orElseThrow().values());
  }

  @Test
  void a_worker_wide_meter_carrying_no_module_tag_belongs_to_whichever_instance_asks() {
    Map<String, Object> fabricClient =
        counter("gimle.fabric.client.request.count", "2026-09-01T14:02:10Z", 12.0);
    fabricClient.put("tags", Map.of("interface", "com.example.Greeter"));

    MetricsHistory history = MetricsHistoryReader.fold(List.of(fabricClient), GREETER);

    assertTrue(history.series("gimle.fabric.client.request.count").isPresent());
  }

  @Test
  void a_control_plane_with_no_observability_sink_behind_it_yields_an_empty_history() {
    FakeClusterReader reader = new FakeClusterReader();
    reader.failWith(new CliException("no muninn endpoint configured"));

    MetricsHistory history =
        new MetricsHistoryReader(reader).read("node-alpha", "worker-4471", GREETER);

    assertTrue(history.isEmpty());
  }

  @Test
  void an_envelope_with_no_lines_at_all_yields_an_empty_history_rather_than_failing() {
    MetricsHistory history =
        new MetricsHistoryReader(new FakeClusterReader())
            .read("node-alpha", "worker-4471", GREETER);

    assertTrue(history.isEmpty());
    assertTrue(history.series(COUNT_METER).isEmpty());
  }

  @Test
  void the_worker_history_route_is_addressed_by_its_node_and_worker_ids() {
    FakeClusterReader reader = new FakeClusterReader();

    new MetricsHistoryReader(reader).read("node-alpha", "worker-4471", GREETER);

    // A worker has no address of its own, so its history is filed under the composite id -- and
    // the colon joining the two is encoded, never left to be read as a path separator.
    assertEquals(List.of(WORKER_PATH), reader.requestedPaths());
    assertFalse(WORKER_PATH.contains(":"), WORKER_PATH);
  }

  private static MetricsHistory read(final List<Map<String, Object>> lines) {
    FakeClusterReader reader =
        new FakeClusterReader().withObject(WORKER_PATH, Map.of("lines", lines));
    return new MetricsHistoryReader(reader).read("node-alpha", "worker-4471", GREETER);
  }

  private static Map<String, Object> counter(
      final String name, final String timestamp, final double count) {
    return meter(name, "COUNTER", timestamp, Map.of("COUNT", count));
  }

  private static Map<String, Object> gauge(
      final String name, final String timestamp, final double value) {
    return meter(name, "GAUGE", timestamp, Map.of("VALUE", value));
  }

  /** One line exactly as the worker's own meter snapshot writes it. */
  private static Map<String, Object> meter(
      final String name,
      final String type,
      final String timestamp,
      final Map<String, Object> measurements) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("timestamp", timestamp);
    line.put("name", name);
    line.put("type", type);
    line.put("tags", Map.of("module", "greeter-consumer", "version", "1.0.0"));
    line.put("measurements", measurements);
    return line;
  }

  @Test
  void a_reading_taken_at_an_instant_the_series_already_holds_is_summed_not_dropped() {
    Map<String, Object> other = counter(COUNT_METER, "2026-09-01T14:02:10Z", 5.0);
    other.put("tags", Map.of("outcome", "retry"));

    MetricsHistory history =
        MetricsHistoryReader.fold(
            List.of(counter(COUNT_METER, "2026-09-01T14:02:10Z", 140.0), other), Optional.empty());

    assertEquals(List.of(145.0), history.series(COUNT_METER).orElseThrow().values());
  }

  @Test
  void a_series_never_shipped_is_absent_rather_than_an_empty_one() {
    MetricsHistory history = read(List.of(counter(COUNT_METER, "2026-09-01T14:02:10Z", 140.0)));

    assertEquals(Optional.empty(), history.series("gimle.module.threads"));
  }

  @Test
  void a_single_reading_yields_no_rate_at_all_because_a_rate_needs_two() {
    MetricSeries requests =
        read(List.of(counter(COUNT_METER, "2026-09-01T14:02:10Z", 140.0)))
            .series(COUNT_METER)
            .orElseThrow();

    assertEquals(List.of(), requests.ratesPerSecond());
    assertEquals(List.of(140.0), requests.values());
  }
}
