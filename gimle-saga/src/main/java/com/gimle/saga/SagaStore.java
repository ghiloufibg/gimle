package com.gimle.saga;

import com.gimle.core.exception.GimleCodecException;
import com.gimle.core.protocol.Json;
import com.gimle.core.saga.SagaEvent;
import com.gimle.core.saga.SagaEventCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saga's file store: one directory per run under {@code runs/}, holding the append-only {@code
 * events.ndjson} (the authority for everything about the run) and a derived, atomically-rewritten
 * {@code meta.json} beside it, plus one cross-run derived index, {@code index/flake-ledger.ndjson}
 * -- one line per flake observation, appended when a run finishes and fully reconstructable via
 * {@link #rebuildLedger()}.
 *
 * <p>Ingest is idempotent by run: a batch that re-opens a known run (carries its run-started event
 * again) replaces that run's files and its ledger lines rather than appending alongside the old
 * ones, so re-shipping a whole run -- the natural retry after a partial upload -- never
 * double-counts anything. Appends to one run are serialized by a per-run lock; a torn trailing line
 * (a crash mid-append) is truncated away before the next append and skipped on every read.
 *
 * <p>A second cross-run derived index, {@code index/test-tags.ndjson}, holds one line per testId
 * with that test's most recently observed JUnit tags -- unlike the flake ledger, this is a
 * current-state index, not a history, so a later observation simply overwrites the earlier one (the
 * same "one authoritative record per key, atomically rewritten" shape as a run's own {@code
 * meta.json}) rather than appending another entry.
 */
public final class SagaStore {

  private static final Logger log = LoggerFactory.getLogger(SagaStore.class);

  /**
   * Allow-list for run IDs, which become directory names under {@code runs/}: no separators or
   * dot-dot, so an ingested run ID can never name a path outside the store.
   */
  private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private static final String EVENTS_FILE = "events.ndjson";
  private static final String META_FILE = "meta.json";

  private final Path runsDir;
  private final Path ledgerFile;
  private final Path testTagsFile;
  private final ConcurrentHashMap<String, ReentrantLock> runLocks = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, List<String>> testTags = new ConcurrentHashMap<>();
  private final Object ledgerLock = new Object();
  private final Object testTagsLock = new Object();

  public SagaStore(Path dataRoot) {
    this.runsDir = dataRoot.resolve("runs");
    final Path indexDir = dataRoot.resolve("index");
    this.ledgerFile = indexDir.resolve("flake-ledger.ndjson");
    this.testTagsFile = indexDir.resolve("test-tags.ndjson");
    try {
      Files.createDirectories(runsDir);
      Files.createDirectories(indexDir);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to create saga data root under " + dataRoot, e);
    }
    markLiveRunsAbandoned();
    loadTestTags();
  }

  /** A slice of one run's stored event lines: raw NDJSON lines plus the cursor to resume from. */
  public record EventsPage(List<String> lines, long nextCursor) {
    public EventsPage {
      lines = List.copyOf(lines);
    }
  }

  /**
   * One run's appearance of a test on its history timeline; outcome/duration are the final
   * attempt's.
   */
  public record TestHistoryEntry(
      String runId,
      long startedAtEpochMilli,
      SagaEvent.Outcome outcome,
      long durationMillis,
      int attempts,
      boolean flaky) {}

  /** One row of the flaky scoreboard, aggregated from the ledger over a time window. */
  public record FlakySummary(
      String testId,
      String module,
      int occurrences,
      int runsSeen,
      double flakeRate,
      double score,
      Map<String, Integer> signatures,
      long firstSeen,
      long lastSeen) {
    public FlakySummary {
      signatures = Map.copyOf(signatures);
    }
  }

  /** Appends a batch of events, grouped by run, and returns the run IDs touched in order. */
  public List<String> ingest(List<SagaEvent> events) {
    Map<String, List<SagaEvent>> byRun = new LinkedHashMap<>();
    for (SagaEvent event : events) {
      byRun.computeIfAbsent(event.runId(), id -> new ArrayList<>()).add(event);
    }
    for (Map.Entry<String, List<SagaEvent>> entry : byRun.entrySet()) {
      ingestRun(entry.getKey(), entry.getValue());
    }
    return List.copyOf(byRun.keySet());
  }

  /**
   * Folds an imported batch into {@code runId} as a safety net for a run whose events already
   * streamed in live: the batch's own run-started/run-finished framing is dropped so a fold can
   * never reset the run, and test events are appended only for testIds the run has no test-finished
   * for yet (a test JVM that died before flushing its events is exactly the gap this covers). When
   * the run doesn't exist at all, the batch is ingested unmodified, framing included, so a
   * standalone import still produces a self-contained run. Returns the number of events actually
   * appended.
   */
  public int fold(String runId, List<SagaEvent> events) {
    validateRunId(runId);
    ReentrantLock lock = runLocks.computeIfAbsent(runId, id -> new ReentrantLock());
    lock.lock();
    try {
      if (!Files.isRegularFile(runsDir.resolve(runId).resolve(EVENTS_FILE))) {
        ingest(events);
        return events.size();
      }
      Set<String> alreadyFinished = new LinkedHashSet<>();
      for (SagaEvent stored : decodeStoredEvents(runId)) {
        if (stored instanceof SagaEvent.TestFinished finished) {
          alreadyFinished.add(finished.testId());
        }
      }
      List<SagaEvent> additions = new ArrayList<>();
      for (SagaEvent event : events) {
        boolean isNewTest =
            switch (event) {
              case SagaEvent.TestStarted e -> !alreadyFinished.contains(e.testId());
              case SagaEvent.TestFinished e -> !alreadyFinished.contains(e.testId());
              default -> false;
            };
        if (isNewTest) {
          additions.add(event);
        }
      }
      if (!additions.isEmpty()) {
        ingestRun(runId, additions);
      }
      return additions.size();
    } finally {
      lock.unlock();
    }
  }

  private void ingestRun(String runId, List<SagaEvent> events) {
    validateRunId(runId);
    ReentrantLock lock = runLocks.computeIfAbsent(runId, id -> new ReentrantLock());
    lock.lock();
    try {
      Path runDir = runsDir.resolve(runId);
      boolean reopens = events.stream().anyMatch(e -> e instanceof SagaEvent.RunStarted);
      if (reopens && Files.exists(runDir)) {
        deleteRecursively(runDir);
        synchronized (ledgerLock) {
          rewriteLedger(runId, List.of());
        }
      }
      Files.createDirectories(runDir);
      appendEvents(runDir.resolve(EVENTS_FILE), events);
      RunMeta meta = loadMeta(runId).orElse(RunMeta.emptyFor(runId));
      for (SagaEvent event : events) {
        meta = meta.fold(event);
      }
      atomicWrite(
          runDir.resolve(META_FILE), Json.write(meta.toJsonMap()).getBytes(StandardCharsets.UTF_8));
      updateTestTags(events);
      if (events.stream().anyMatch(e -> e instanceof SagaEvent.RunFinished)) {
        List<FlakeObservation> observations =
            deriveFlakeObservations(runId, decodeStoredEvents(runId), meta.finishedAtEpochMilli());
        synchronized (ledgerLock) {
          rewriteLedger(runId, observations);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed to ingest events for run " + runId, e);
    } finally {
      lock.unlock();
    }
  }

  public List<RunMeta> listRuns(int limit) {
    List<RunMeta> runs = new ArrayList<>();
    for (String runId : runDirNames()) {
      loadOrReconstructMeta(runId).ifPresent(runs::add);
    }
    runs.sort(
        Comparator.comparingLong(RunMeta::startedAtEpochMilli)
            .reversed()
            .thenComparing(RunMeta::runId));
    return List.copyOf(runs.subList(0, Math.min(limit, runs.size())));
  }

  public Optional<RunMeta> run(String runId) {
    validateRunId(runId);
    return loadOrReconstructMeta(runId);
  }

  /** Reads stored event lines starting at {@code cursor} (a completed-line offset from 0). */
  public EventsPage readEvents(String runId, long cursor) {
    validateRunId(runId);
    Path file = runsDir.resolve(runId).resolve(EVENTS_FILE);
    if (!Files.isRegularFile(file)) {
      return new EventsPage(List.of(), cursor);
    }
    List<String> complete = completeLines(file);
    int from = (int) Math.min(Math.max(cursor, 0), complete.size());
    return new EventsPage(complete.subList(from, complete.size()), complete.size());
  }

  public List<FlakeObservation> flakeObservations() {
    synchronized (ledgerLock) {
      if (!Files.isRegularFile(ledgerFile)) {
        return List.of();
      }
      List<FlakeObservation> observations = new ArrayList<>();
      for (String line : completeLines(ledgerFile)) {
        try {
          observations.add(FlakeObservation.fromJsonLine(line));
        } catch (RuntimeException e) {
          log.warn("skipping unparseable flake ledger line: {}", e.getMessage());
        }
      }
      return List.copyOf(observations);
    }
  }

  /**
   * Rebuilds the flake ledger from scratch by rescanning every finished run's events -- the ledger
   * is derived state, so this reproduces it exactly (runs ordered by start time for determinism).
   */
  public void rebuildLedger() {
    List<RunMeta> finished =
        listRuns(Integer.MAX_VALUE).stream().filter(m -> m.finishedAtEpochMilli() > 0).toList();
    List<FlakeObservation> observations = new ArrayList<>();
    for (RunMeta meta :
        finished.stream()
            .sorted(
                Comparator.comparingLong(RunMeta::startedAtEpochMilli)
                    .thenComparing(RunMeta::runId))
            .toList()) {
      observations.addAll(
          deriveFlakeObservations(
              meta.runId(), decodeStoredEvents(meta.runId()), meta.finishedAtEpochMilli()));
    }
    synchronized (ledgerLock) {
      StringBuilder content = new StringBuilder();
      for (FlakeObservation observation : observations) {
        content.append(observation.toJsonLine()).append('\n');
      }
      atomicWrite(ledgerFile, content.toString().getBytes(StandardCharsets.UTF_8));
    }
  }

  /**
   * The flaky-test scoreboard over observations dated at or after {@code sinceEpochMilli}. {@code
   * runsSeen} counts runs started in the same window whose events include the test at all, so
   * {@code flakeRate} reads as "of the runs that exercised this test lately, what fraction flaked."
   */
  public List<FlakySummary> flakyScoreboard(long sinceEpochMilli) {
    Map<String, List<FlakeObservation>> byTest = new LinkedHashMap<>();
    for (FlakeObservation observation : flakeObservations()) {
      if (observation.dateEpochMilli() >= sinceEpochMilli) {
        byTest.computeIfAbsent(observation.testId(), id -> new ArrayList<>()).add(observation);
      }
    }
    if (byTest.isEmpty()) {
      return List.of();
    }
    Map<String, Integer> runsSeen = new LinkedHashMap<>();
    for (RunMeta meta : listRuns(Integer.MAX_VALUE)) {
      if (meta.startedAtEpochMilli() < sinceEpochMilli) {
        continue;
      }
      Set<String> testIds = testIdsIn(meta.runId());
      for (String testId : byTest.keySet()) {
        if (testIds.contains(testId)) {
          runsSeen.merge(testId, 1, Integer::sum);
        }
      }
    }
    List<FlakySummary> summaries = new ArrayList<>();
    for (Map.Entry<String, List<FlakeObservation>> entry : byTest.entrySet()) {
      String testId = entry.getKey();
      List<FlakeObservation> observations = entry.getValue();
      int occurrences = observations.size();
      int seen = Math.max(runsSeen.getOrDefault(testId, 0), occurrences);
      double flakeRate = (double) occurrences / seen;
      Map<String, Integer> signatures = new LinkedHashMap<>();
      long firstSeen = Long.MAX_VALUE;
      long lastSeen = Long.MIN_VALUE;
      for (FlakeObservation observation : observations) {
        signatures.merge(observation.failureSignature(), 1, Integer::sum);
        firstSeen = Math.min(firstSeen, observation.dateEpochMilli());
        lastSeen = Math.max(lastSeen, observation.dateEpochMilli());
      }
      int colon = testId.indexOf(':');
      String module = colon > 0 ? testId.substring(0, colon) : "";
      summaries.add(
          new FlakySummary(
              testId,
              module,
              occurrences,
              seen,
              flakeRate,
              flakeRate * seen,
              signatures,
              firstSeen,
              lastSeen));
    }
    summaries.sort(
        Comparator.comparingDouble(FlakySummary::score)
            .reversed()
            .thenComparing(FlakySummary::testId));
    return List.copyOf(summaries);
  }

  /** One entry per run (newest first) in which {@code testId} ran at least one attempt. */
  public List<TestHistoryEntry> testHistory(String testId) {
    List<TestHistoryEntry> history = new ArrayList<>();
    for (RunMeta meta : listRuns(Integer.MAX_VALUE)) {
      List<SagaEvent.TestFinished> finishes = new ArrayList<>();
      for (SagaEvent event : decodeStoredEvents(meta.runId())) {
        if (event instanceof SagaEvent.TestFinished finished && finished.testId().equals(testId)) {
          finishes.add(finished);
        }
      }
      if (finishes.isEmpty()) {
        continue;
      }
      finishes.sort(Comparator.comparingInt(SagaEvent.TestFinished::attempt));
      SagaEvent.TestFinished last = finishes.get(finishes.size() - 1);
      boolean flaky = false;
      for (SagaEvent.TestFinished finished : finishes) {
        if (finished.outcome() == SagaEvent.Outcome.FAILED
            && passedAttemptExists(finishes, finished.attempt() + 1)) {
          flaky = true;
        }
      }
      history.add(
          new TestHistoryEntry(
              meta.runId(),
              meta.startedAtEpochMilli(),
              last.outcome(),
              last.durationMillis(),
              finishes.size(),
              flaky));
    }
    return List.copyOf(history);
  }

  /**
   * True iff {@code testId}'s most recently observed tag set includes {@code "flaky"} -- the
   * quarantine marker a test author adds via {@code @Tag("flaky")} once it's a known offender.
   * Answers {@code false} for a testId this store has never seen a {@code TestStarted} for.
   */
  public boolean quarantined(String testId) {
    return testTags.getOrDefault(testId, List.of()).contains("flaky");
  }

  // ---- test tags index ----

  /**
   * Folds every {@link SagaEvent.TestStarted} in the batch into the in-memory tag index, then
   * rewrites the whole index file once -- the same one-atomic-write-per-batch shape {@code
   * meta.json} already uses, not one write per event.
   */
  private void updateTestTags(List<SagaEvent> events) {
    boolean changed = false;
    for (SagaEvent event : events) {
      if (event instanceof SagaEvent.TestStarted started) {
        testTags.put(started.testId(), List.copyOf(started.tags()));
        changed = true;
      }
    }
    if (!changed) {
      return;
    }
    synchronized (testTagsLock) {
      atomicWrite(testTagsFile, serializeTestTags());
    }
  }

  private byte[] serializeTestTags() {
    StringBuilder content = new StringBuilder();
    // Sorted by testId so the file is diff-friendly and its order doesn't depend on ingest order.
    for (Map.Entry<String, List<String>> entry : new TreeMap<>(testTags).entrySet()) {
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("testId", entry.getKey());
      line.put("tags", entry.getValue());
      content.append(Json.write(line)).append('\n');
    }
    return content.toString().getBytes(StandardCharsets.UTF_8);
  }

  private void loadTestTags() {
    if (!Files.isRegularFile(testTagsFile)) {
      return;
    }
    for (String line : completeLines(testTagsFile)) {
      try {
        Map<String, Object> json = Json.asObject(Json.parse(line));
        String testId = String.valueOf(json.get("testId"));
        List<String> tags =
            Json.asArray(json.getOrDefault("tags", List.of())).stream()
                .map(String::valueOf)
                .toList();
        testTags.put(testId, tags);
      } catch (RuntimeException e) {
        log.warn("skipping unparseable test-tags index line: {}", e.getMessage());
      }
    }
  }

  // ---- flake derivation ----

  /**
   * The flake rule: a FAILED attempt N immediately followed by a PASSED attempt N+1 of the same
   * test in the same run is one observation -- exactly Surefire's own rerun-pass semantics.
   */
  static List<FlakeObservation> deriveFlakeObservations(
      String runId, List<SagaEvent> events, long dateEpochMilli) {
    Map<String, List<SagaEvent.TestFinished>> byTest = new LinkedHashMap<>();
    for (SagaEvent event : events) {
      if (event instanceof SagaEvent.TestFinished finished) {
        byTest.computeIfAbsent(finished.testId(), id -> new ArrayList<>()).add(finished);
      }
    }
    List<FlakeObservation> observations = new ArrayList<>();
    for (Map.Entry<String, List<SagaEvent.TestFinished>> entry : byTest.entrySet()) {
      for (SagaEvent.TestFinished finished : entry.getValue()) {
        if (finished.outcome() == SagaEvent.Outcome.FAILED
            && passedAttemptExists(entry.getValue(), finished.attempt() + 1)) {
          observations.add(
              new FlakeObservation(
                  entry.getKey(), runId, dateEpochMilli, finished.failureSignature().orElse("")));
        }
      }
    }
    return List.copyOf(observations);
  }

  private static boolean passedAttemptExists(List<SagaEvent.TestFinished> finishes, int attempt) {
    for (SagaEvent.TestFinished finished : finishes) {
      if (finished.attempt() == attempt && finished.outcome() == SagaEvent.Outcome.PASSED) {
        return true;
      }
    }
    return false;
  }

  // ---- file plumbing ----

  private void markLiveRunsAbandoned() {
    for (String runId : runDirNames()) {
      Optional<RunMeta> meta = loadOrReconstructMeta(runId);
      if (meta.isPresent() && meta.get().status() == RunMeta.RunStatus.LIVE) {
        atomicWrite(
            runsDir.resolve(runId).resolve(META_FILE),
            Json.write(meta.get().abandoned().toJsonMap()).getBytes(StandardCharsets.UTF_8));
        log.info("marked run {} abandoned: it was still live at startup", runId);
      }
    }
  }

  private List<String> runDirNames() {
    if (!Files.isDirectory(runsDir)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(runsDir)) {
      return entries.filter(Files::isDirectory).map(SagaStore::fileNameString).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException("failed to list runs under " + runsDir, e);
    }
  }

  // getFileName() only returns null for a zero-element path, which a Files.list entry never is;
  // the guard lives here once rather than at every call site.
  private static String fileNameString(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      throw new IllegalStateException("expected a named path, got " + path);
    }
    return fileName.toString();
  }

  private Optional<RunMeta> loadMeta(String runId) {
    Path file = runsDir.resolve(runId).resolve(META_FILE);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(RunMeta.fromJsonMap(Json.asObject(Json.parse(Files.readString(file)))));
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read " + file, e);
    }
  }

  private Optional<RunMeta> loadOrReconstructMeta(String runId) {
    Optional<RunMeta> stored = loadMeta(runId);
    if (stored.isPresent()) {
      return stored;
    }
    if (!Files.isRegularFile(runsDir.resolve(runId).resolve(EVENTS_FILE))) {
      return Optional.empty();
    }
    RunMeta meta = RunMeta.emptyFor(runId);
    for (SagaEvent event : decodeStoredEvents(runId)) {
      meta = meta.fold(event);
    }
    return Optional.of(meta);
  }

  private List<SagaEvent> decodeStoredEvents(String runId) {
    List<SagaEvent> events = new ArrayList<>();
    for (String line : readEvents(runId, 0).lines()) {
      try {
        events.add(SagaEventCodec.decode(line));
      } catch (GimleCodecException e) {
        log.warn("skipping undecodable stored event line in run {}: {}", runId, e.getMessage());
      }
    }
    return events;
  }

  private Set<String> testIdsIn(String runId) {
    Set<String> testIds = new LinkedHashSet<>();
    for (SagaEvent event : decodeStoredEvents(runId)) {
      if (event instanceof SagaEvent.TestFinished finished) {
        testIds.add(finished.testId());
      }
    }
    return testIds;
  }

  private static void appendEvents(Path file, List<SagaEvent> events) throws IOException {
    truncateTornTail(file);
    StringBuilder batch = new StringBuilder();
    for (SagaEvent event : events) {
      batch.append(SagaEventCodec.encode(event)).append('\n');
    }
    Files.write(
        file,
        batch.toString().getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  /**
   * A crash mid-append can leave a final line without its newline; appending after it would fuse
   * the fragment with the next event into one unreadable line, so the fragment is cut back to the
   * last completed line before any append.
   */
  private static void truncateTornTail(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return;
    }
    byte[] all = Files.readAllBytes(file);
    if (all.length == 0 || all[all.length - 1] == '\n') {
      return;
    }
    int lastNewline = -1;
    for (int i = all.length - 1; i >= 0; i--) {
      if (all[i] == '\n') {
        lastNewline = i;
        break;
      }
    }
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
      channel.truncate(lastNewline + 1L);
    }
  }

  /** Complete (newline-terminated) lines only -- a torn trailing fragment is never surfaced. */
  private static List<String> completeLines(Path file) {
    byte[] all;
    try {
      all = Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read " + file, e);
    }
    String text = new String(all, StandardCharsets.UTF_8);
    List<String> lines = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        lines.add(text.substring(start, i));
        start = i + 1;
      }
    }
    return lines;
  }

  /**
   * Rewrites the ledger with {@code runId}'s old lines removed and {@code replacement} appended.
   */
  private void rewriteLedger(String runId, List<FlakeObservation> replacement) {
    List<String> kept = new ArrayList<>();
    if (Files.isRegularFile(ledgerFile)) {
      for (String line : completeLines(ledgerFile)) {
        try {
          if (!FlakeObservation.fromJsonLine(line).runId().equals(runId)) {
            kept.add(line);
          }
        } catch (RuntimeException e) {
          log.warn("dropping unparseable flake ledger line: {}", e.getMessage());
        }
      }
    }
    StringBuilder content = new StringBuilder();
    for (String line : kept) {
      content.append(line).append('\n');
    }
    for (FlakeObservation observation : replacement) {
      content.append(observation.toJsonLine()).append('\n');
    }
    atomicWrite(ledgerFile, content.toString().getBytes(StandardCharsets.UTF_8));
  }

  /** The repo's rewrite idiom: temp file beside the target, then an atomic rename over it. */
  private static void atomicWrite(Path target, byte[] content) {
    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
    try {
      Files.write(tmp, content);
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to atomically write " + target, e);
    }
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> tree = Files.walk(dir)) {
      for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }

  private static void validateRunId(String runId) {
    if (runId == null || !RUN_ID.matcher(runId).matches()) {
      throw new IllegalArgumentException("invalid runId: " + runId);
    }
  }
}
