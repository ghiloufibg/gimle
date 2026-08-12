package com.gimle.observability;

import com.gimle.core.logging.LogFileReader;
import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared batch-POST-with-retry class every shipping process (an agent, or a standalone
 * control-plane/Fafnir/mimir replica) instantiates to send logs, metrics, or trace batches to
 * Muninn -- one instance per shipped stream, bound at construction to Muninn's address and the one
 * ingest path it ships to (the caller bakes {@code processKind}/{@code processId} or {@code
 * nodeId}/category into that path itself, e.g. {@code /ingest/metrics/FAFNIR/127.0.0.1:9092}), so a
 * process with several streams to ship (an agent shipping its own logs plus every supervised
 * worker's) simply constructs several instances.
 *
 * <p>Runs its periodic tick on its own virtual thread ({@code Thread.ofVirtual()}), the same
 * lightweight-background-work idiom {@code AgentMain}'s own instance-starter thread already uses --
 * never the caller's thread, so a slow or unreachable Muninn only ever delays this one shipper's
 * next tick. Deliberately best-effort: a failed POST just logs at {@code debug} and retries next
 * tick with the same unshipped cursor (logs only -- metrics/traces have no cursor to preserve, a
 * missed tick's data is simply not re-sent), the same degrade-don't-fail posture {@link
 * ThreadNameJfrAttributor} already applies when JFR itself is unavailable. The log-shipping cursor
 * is deliberately in-memory only, not persisted to disk: a process restart re-ships from "nothing
 * shipped yet," accepting a small duplicate window in Muninn's own history rather than adding a
 * second persisted-cursor mechanism next to {@code LogFileReader}'s own cursor semantics.
 */
public final class MuninnShipper implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(MuninnShipper.class);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final URI baseUri;
  private final String ingestPath;
  private final Duration tickInterval;
  private final HttpClient httpClient;
  private volatile ScheduledExecutorService ticker;
  private volatile String logCursor;

  /**
   * When non-null, ticks are scheduled here instead of on a thread this class owns, and {@link
   * #close()} leaves it alone -- whoever supplied it owns its lifecycle. Package-private, for the
   * one caller that needs it: a test driving shipping tick by tick (see {@code TestScheduler} in
   * {@code gimle-core}'s test-jar), so "no further tick fired" is an exact assertion rather than a
   * pause and a hope. Production always gets the owned virtual-thread ticker below.
   */
  private final ScheduledExecutorService injectedTicker;

  public MuninnShipper(String muninnBaseAddress, String ingestPath, Duration tickInterval) {
    this(muninnBaseAddress, ingestPath, tickInterval, defaultSslContext());
  }

  MuninnShipper(
      String muninnBaseAddress,
      String ingestPath,
      Duration tickInterval,
      Optional<SSLContext> sslContext) {
    this(muninnBaseAddress, ingestPath, tickInterval, sslContext, null);
  }

  /** See {@link #injectedTicker}. */
  MuninnShipper(
      String muninnBaseAddress,
      String ingestPath,
      Duration tickInterval,
      Optional<SSLContext> sslContext,
      ScheduledExecutorService injectedTicker) {
    this.injectedTicker = injectedTicker;
    String scheme = sslContext.isPresent() ? "https" : "http";
    this.baseUri = URI.create(scheme + "://" + muninnBaseAddress);
    this.ingestPath = ingestPath;
    this.tickInterval = tickInterval;
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT);
    sslContext.ifPresent(builder::sslContext);
    this.httpClient = builder.build();
  }

  private static Optional<SSLContext> defaultSslContext() {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return Optional.empty();
    }
    return Optional.of(SslContexts.forMutualTls(TlsSettings.fromConfig()));
  }

  /**
   * Starts a periodic tick reading newly-appended lines from {@code activeFile} (via {@link
   * LogFileReader#readAfter}, the same cursor semantics {@code AgentLogServer}'s own polling
   * already relies on) and shipping them as NDJSON. The cursor advances only on a successful (2xx)
   * ship, so a failed tick re-sends the same unshipped lines next time rather than losing them.
   */
  public void startShippingLogFile(Path activeFile, int maxFiles) {
    startTicker(() -> tickLogs(activeFile, maxFiles));
  }

  /**
   * Starts a periodic tick snapshotting every meter currently in {@code registry} and shipping one
   * NDJSON line per meter -- a periodic push, not a pull-based scrape endpoint (design doc §5b/§5f:
   * no Prometheus-shaped exporter). {@link Meter#measure()}'s generic {@code Statistic}-keyed
   * iteration is what makes this uniform across Counter/Gauge/Timer/DistributionSummary without
   * hand-special-casing each meter type.
   */
  public void startShippingMetrics(MeterRegistry registry) {
    startTicker(() -> tickMetrics(registry));
  }

  /**
   * One-shot, best-effort: hands a pre-built batch of already-serialized span lines to Muninn
   * immediately, no tick/cursor involved -- {@code MuninnSpanExporter}'s own {@code
   * BatchSpanProcessor} already owns the batching decision on the OpenTelemetry SDK side, so this
   * method doesn't duplicate it.
   */
  public void shipTraceBatch(List<Map<String, Object>> spanLines) {
    if (spanLines.isEmpty()) {
      return;
    }
    postNdjson(spanLines);
  }

  private void startTicker(Runnable tick) {
    ScheduledExecutorService scheduler =
        injectedTicker != null
            ? injectedTicker
            : Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofVirtual().name("gimle-muninn-shipper-" + ingestPath).unstarted(r));
    scheduler.scheduleAtFixedRate(tick, 0, tickInterval.toMillis(), TimeUnit.MILLISECONDS);
    this.ticker = scheduler;
  }

  private void tickLogs(Path activeFile, int maxFiles) {
    try {
      List<Map<String, Object>> lines = LogFileReader.readAfter(activeFile, maxFiles, logCursor);
      if (lines.isEmpty()) {
        return;
      }
      if (postNdjson(lines)) {
        logCursor = String.valueOf(lines.get(lines.size() - 1).get("timestamp"));
      }
    } catch (RuntimeException e) {
      log.debug("log shipping tick to {} failed: {}", ingestPath, e.getMessage());
    }
  }

  private void tickMetrics(MeterRegistry registry) {
    try {
      List<Map<String, Object>> lines = new ArrayList<>();
      for (Meter meter : registry.getMeters()) {
        lines.add(meterToJsonLine(meter));
      }
      if (!lines.isEmpty()) {
        postNdjson(lines);
      }
    } catch (RuntimeException e) {
      log.debug("metrics shipping tick to {} failed: {}", ingestPath, e.getMessage());
    }
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

  /**
   * Returns {@code true} iff the batch was accepted (2xx) -- the caller decides what that means.
   */
  private boolean postNdjson(List<Map<String, Object>> lines) {
    StringBuilder body = new StringBuilder();
    for (Map<String, Object> line : lines) {
      body.append(Json.write(line)).append('\n');
    }
    try {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(baseUri.resolve(ingestPath))
                  .timeout(REQUEST_TIMEOUT)
                  .header("Content-Type", "application/x-ndjson")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      boolean accepted = response.statusCode() >= 200 && response.statusCode() < 300;
      if (!accepted) {
        log.debug(
            "muninn ingest {} rejected batch with status {}", ingestPath, response.statusCode());
      }
      return accepted;
    } catch (IOException e) {
      log.debug("failed to ship batch to muninn at {}: {}", ingestPath, e.getMessage());
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void close() {
    ScheduledExecutorService current = ticker;
    if (current != null && current != injectedTicker) {
      current.shutdownNow();
    }
    httpClient.close();
  }
}
