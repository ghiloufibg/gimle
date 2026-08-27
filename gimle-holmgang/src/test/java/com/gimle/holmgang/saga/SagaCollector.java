package com.gimle.holmgang.saga;

import com.gimle.holmgang.topology.ClusterSpec;
import com.gimle.ragnarok.fenrir.ChaosLedger;
import com.gimle.ragnarok.surtr.SurtrReport;
import com.gimle.ragnarok.surtr.SurtrRunResult;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one process-wide sink a validation run's report is assembled in: scenario results fed by the
 * {@link SagaCucumberPlugin}, plus Fenrir ledgers, Surtr results, and booted topologies folded in
 * from wherever they arise. Everything a run produces accumulates here across the whole failsafe
 * fork -- the Gherkin suite and the Java {@code *IT}s share one JVM -- and is flushed once to a
 * versioned {@code holmgang-report.json} at JVM shutdown, so the report captures the entire run
 * regardless of the order its parts ran in. That same flush also hands everything to {@link
 * SagaShipper}, which best-effort-ships it onward as attachments to a running Saga report server
 * when one is configured -- a second output path, not a replacement for the local report.
 *
 * <p>The collector is created lazily on first use, so a plain unit build (which touches none of
 * this) never writes a report; only a real {@code -Pvalidation} run does.
 */
public final class SagaCollector {

  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
  private static final Pattern FORENSIC_PATH = Pattern.compile("([\\w./-]*forensic-report\\.txt)");
  private static final int MAX_MESSAGE_CHARS = 4000;

  private static volatile SagaCollector instance;

  private final String runId;
  private final long startedAtMillis;
  private final Map<String, Feature> features = new LinkedHashMap<>();
  private final List<Map<String, Object>> fenrirRuns = new ArrayList<>();
  private final List<Map<String, Object>> surtrRuns = new ArrayList<>();
  private final Map<String, Map<String, Object>> topologies = new LinkedHashMap<>();
  private volatile boolean written;

  private SagaCollector(final boolean registerShutdownHook) {
    this.startedAtMillis = System.currentTimeMillis();
    this.runId = "holmgang-" + STAMP.format(Instant.ofEpochMilli(startedAtMillis));
    if (registerShutdownHook) {
      Runtime.getRuntime().addShutdownHook(new Thread(this::flush, "saga-writer"));
    }
  }

  /** The lazily-created singleton; the first call also arms the shutdown-hook flush. */
  public static SagaCollector instance() {
    if (instance == null) {
      synchronized (SagaCollector.class) {
        if (instance == null) {
          instance = new SagaCollector(true);
        }
      }
    }
    return instance;
  }

  /** A standalone collector with no shutdown hook, for unit tests of the assembled report. */
  static SagaCollector forTest() {
    return new SagaCollector(false);
  }

  /** One accumulated feature and its scenarios, in encounter order. */
  private static final class Feature {
    private final String name;
    private final String file;
    private final List<Map<String, Object>> scenarios = new ArrayList<>();

    Feature(final String name, final String file) {
      this.name = name;
      this.file = file;
    }
  }

  public synchronized void addScenario(
      final String featureName, final String featureFile, final Map<String, Object> scenarioJson) {
    features
        .computeIfAbsent(featureFile, k -> new Feature(featureName, featureFile))
        .scenarios
        .add(scenarioJson);
  }

  /**
   * The report's {@code failure} shape for a throwable: its message (truncated), and a forensic
   * report path lifted from that message when one is named. Shared by the Cucumber plugin and the
   * JUnit listener so both represent a failure identically.
   */
  public static Map<String, Object> failureFrom(final Throwable error) {
    if (error == null) {
      return null;
    }
    String message = error.getMessage();
    if (message == null) {
      message = error.getClass().getSimpleName();
    }
    final Matcher pathMatcher = FORENSIC_PATH.matcher(message);
    final String forensicPath = pathMatcher.find() ? pathMatcher.group(1) : null;
    if (message.length() > MAX_MESSAGE_CHARS) {
      message = message.substring(0, MAX_MESSAGE_CHARS) + "\n… (truncated)";
    }
    final Map<String, Object> failure = new LinkedHashMap<>();
    failure.put("message", message);
    failure.put("forensicReportPath", forensicPath);
    return failure;
  }

  /** Folds one Fenrir soak's ledger into the report. */
  public synchronized void recordFenrir(
      final String scenario, final ChaosLedger ledger, final long soakMillis) {
    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("scenario", scenario);
    run.put("seed", ledger.seed());
    run.put("soakMillis", soakMillis);
    run.put("planned", ledger.strikeCount());
    run.put("executed", ledger.executedCount());
    run.put("skipped", ledger.skippedCount());
    run.put("recovered", ledger.recoveredCount());
    final List<Object> entries = new ArrayList<>();
    for (final ChaosLedger.Entry e : ledger.entries()) {
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("index", e.index());
      row.put("kind", e.kind().name());
      row.put("victim", e.victim());
      row.put("firedAtOffsetMillis", e.firedAtOffsetMillis());
      row.put("outcome", e.outcome().name());
      row.put("recoveryMillis", e.recoveryMillis() >= 0 ? e.recoveryMillis() : null);
      row.put("skipReason", e.skipReason());
      entries.add(row);
    }
    run.put("ledger", entries);
    fenrirRuns.add(run);
  }

  /** Folds one Surtr run's measurements and gates into the report. */
  public synchronized void recordSurtr(final SurtrRunResult result) {
    surtrRuns.add(SurtrReport.reportRun(result));
  }

  /** Registers a booted topology once, keyed by name. */
  public synchronized void recordTopology(final ClusterSpec spec) {
    topologies.computeIfAbsent(
        spec.name(),
        name -> {
          final Map<String, Object> t = new LinkedHashMap<>();
          t.put("name", spec.name());
          t.put("transport", spec.transport().name());
          t.put("stores", spec.storeReplicas());
          t.put("controlPlanes", spec.controlPlaneReplicas());
          t.put("fafnirs", spec.fafnirReplicas());
          t.put("muninn", spec.muninnEnabled());
          t.put("nodes", spec.nodes().size());
          t.put("faultsProxied", spec.faultsProxied());
          return t;
        });
  }

  /** Writes the report exactly once; a no-op if nothing was collected or it already wrote. */
  public synchronized void flush() {
    if (written) {
      return;
    }
    if (features.isEmpty() && fenrirRuns.isEmpty() && surtrRuns.isEmpty()) {
      return;
    }
    written = true;
    SagaWriter.write(this);
    SagaShipper.ship(this);
  }

  String runId() {
    return runId;
  }

  long startedAtMillis() {
    return startedAtMillis;
  }

  List<Map<String, Object>> topologies() {
    return new ArrayList<>(topologies.values());
  }

  List<Map<String, Object>> fenrirRuns() {
    return fenrirRuns;
  }

  List<Map<String, Object>> surtrRuns() {
    return surtrRuns;
  }

  /** The features and their scenarios, in encounter order, as JSON-ready maps. */
  List<Map<String, Object>> featuresJson() {
    final List<Map<String, Object>> out = new ArrayList<>();
    for (final Feature f : features.values()) {
      final Map<String, Object> fm = new LinkedHashMap<>();
      fm.put("name", f.name);
      fm.put("file", f.file);
      fm.put("scenarios", f.scenarios);
      out.add(fm);
    }
    return out;
  }

  /** Every recorded scenario map flattened, for computing totals. */
  List<Map<String, Object>> allScenarios() {
    final List<Map<String, Object>> out = new ArrayList<>();
    for (final Feature f : features.values()) {
      out.addAll(f.scenarios);
    }
    return out;
  }
}
