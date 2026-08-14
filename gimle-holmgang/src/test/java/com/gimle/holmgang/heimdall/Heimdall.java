package com.gimle.holmgang.heimdall;

import com.gimle.core.protocol.Json;
import com.gimle.holmgang.cluster.GimleProcess;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * The cluster watcher every condition and invariant hangs off. Three event sources feed it: every
 * process's own exit (via {@code Process.onExit} -- unexpected deaths fail all pending conditions
 * immediately with the real cause instead of letting them time out), the platform's own log follow
 * streams (used by log conditions, see {@link LogConditions}), and one shared poller -- exactly one
 * per cluster, not one per condition -- that snapshots {@code GET /deployments} + {@code GET
 * /nodes} into a {@link ClusterView} every {@link #POLL_INTERVAL}, rotating across control-plane
 * replicas so every replica's view of shared state is continuously exercised. Conditions are
 * predicates over views: N concurrent conditions cost the same one poller, and each completes on
 * the first view that satisfies it.
 */
public final class Heimdall implements AutoCloseable {

  private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
  private static final int RING_CAPACITY = 40;
  private static final int PLATFORM_EVENTS_PER_INSTANCE = 5;

  private final List<String> controlPlaneBaseUrls;
  private final List<GimleProcess> processes;
  private final Path workDir;
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final CopyOnWriteArrayList<ViewCondition> viewConditions = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<LogWatch> logWatches = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<InvariantGuard> guards = new CopyOnWriteArrayList<>();
  private final Deque<String> recentEvents = new ArrayDeque<>();
  private final Map<String, String> lastDeploymentSummaries = new LinkedHashMap<>();
  private volatile ClusterView lastView;
  private volatile boolean running = true;

  record ViewCondition(
      String description,
      OptionalInt replicaFilter,
      Optional<String> deploymentHint,
      Predicate<ClusterView> predicate,
      CompletableFuture<Void> future) {}

  record LogWatch(String description, CompletableFuture<Void> future) {}

  public static Heimdall attach(
      final List<String> controlPlaneBaseUrls,
      final List<GimleProcess> processes,
      final Path workDir) {
    return new Heimdall(controlPlaneBaseUrls, processes, workDir);
  }

  private Heimdall(
      final List<String> controlPlaneBaseUrls,
      final List<GimleProcess> processes,
      final Path workDir) {
    this.controlPlaneBaseUrls = List.copyOf(controlPlaneBaseUrls);
    this.processes = List.copyOf(processes);
    this.workDir = workDir;
    for (final GimleProcess process : this.processes) {
      process.onExit(() -> onProcessExit(process));
    }
    Thread.ofVirtual().name("heimdall-view-poller").start(this::pollLoop);
  }

  public HeimdallScope scope(final OptionalInt replicaFilter) {
    return new HeimdallScope(this, replicaFilter);
  }

  public InvariantGuard hold(final Invariant invariant) {
    final InvariantGuard guard = new InvariantGuard(this, invariant);
    guards.add(guard);
    recordEvent("invariant held: " + invariant.description());
    final ClusterView view = lastView;
    if (view != null) {
      guard.evaluate(view);
    }
    return guard;
  }

  @Override
  public void close() {
    running = false;
    for (final ViewCondition condition : viewConditions) {
      condition.future().cancel(true);
    }
    for (final LogWatch watch : logWatches) {
      watch.future().cancel(true);
    }
  }

  // ---- registration (called by the condition builders) ----

  HolmgangCondition registerViewCondition(
      final String description,
      final OptionalInt replicaFilter,
      final Optional<String> deploymentHint,
      final Predicate<ClusterView> predicate) {
    final CompletableFuture<Void> future = new CompletableFuture<>();
    final ViewCondition condition =
        new ViewCondition(description, replicaFilter, deploymentHint, predicate, future);
    viewConditions.add(condition);
    final ClusterView view = lastView;
    if (view != null) {
      evaluate(condition, view);
    }
    return new HolmgangCondition(this, description, deploymentHint, future);
  }

  HolmgangCondition registerLogCondition(
      final String description,
      final OptionalInt replicaFilter,
      final String deploymentName,
      final int instanceIndex,
      final String category,
      final String expectedText) {
    final CompletableFuture<Void> future = new CompletableFuture<>();
    final LogWatch watch = new LogWatch(description, future);
    logWatches.add(watch);
    Thread.ofVirtual()
        .name("heimdall-log-" + deploymentName + "-" + instanceIndex)
        .start(
            () ->
                followLog(
                    watch, replicaFilter, deploymentName, instanceIndex, category, expectedText));
    return new HolmgangCondition(this, description, Optional.of(deploymentName), future);
  }

  // ---- event delivery ----

  private void pollLoop() {
    long tick = 0;
    boolean lastPollFailed = false;
    while (running) {
      final int replica = (int) (tick % controlPlaneBaseUrls.size());
      tick++;
      final Optional<ClusterView> view = fetchView(replica);
      if (view.isPresent()) {
        lastPollFailed = false;
        deliver(view.get());
      } else if (!lastPollFailed) {
        lastPollFailed = true;
        recordEvent("view poll failed via control-plane replica #" + replica);
      }
      try {
        Thread.sleep(POLL_INTERVAL);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void deliver(final ClusterView view) {
    lastView = view;
    recordTransitions(view);
    for (final ViewCondition condition : viewConditions) {
      evaluate(condition, view);
    }
    for (final InvariantGuard guard : guards) {
      guard.evaluate(view);
    }
  }

  private void evaluate(final ViewCondition condition, final ClusterView view) {
    if (condition.future().isDone()) {
      viewConditions.remove(condition);
      return;
    }
    if (condition.replicaFilter().isPresent()
        && condition.replicaFilter().getAsInt() != view.sourceReplica()) {
      return;
    }
    if (condition.predicate().test(view)) {
      viewConditions.remove(condition);
      if (condition.future().complete(null)) {
        recordEvent("condition satisfied: " + condition.description());
      }
    }
  }

  private void onProcessExit(final GimleProcess process) {
    recordEvent(
        "process exited: "
            + process.role()
            + " "
            + process.id()
            + (process.exitWasExpected() ? " (by harness)" : " (UNEXPECTED)"));
    if (process.exitWasExpected() || !running) {
      return;
    }
    final HolmgangConditionError error =
        conditionError(
            "cluster process "
                + process.role()
                + " "
                + process.id()
                + " died unexpectedly while conditions were pending",
            Optional.empty());
    for (final ViewCondition condition : viewConditions) {
      condition.future().completeExceptionally(error);
      viewConditions.remove(condition);
    }
    for (final LogWatch watch : logWatches) {
      watch.future().completeExceptionally(error);
      logWatches.remove(watch);
    }
  }

  private void recordTransitions(final ClusterView view) {
    for (final ClusterView.DeploymentView deployment : view.deployments().values()) {
      final String summary = deployment.summarize();
      final String previous = lastDeploymentSummaries.put(deployment.name(), summary);
      if (!summary.equals(previous)) {
        recordEvent(summary);
      }
    }
  }

  private void recordEvent(final String event) {
    synchronized (recentEvents) {
      recentEvents.addLast(Instant.now() + " " + event);
      while (recentEvents.size() > RING_CAPACITY) {
        recentEvents.removeFirst();
      }
    }
  }

  private List<String> recentEventsSnapshot() {
    synchronized (recentEvents) {
      return List.copyOf(recentEvents);
    }
  }

  // ---- fetching ----

  private Optional<ClusterView> fetchView(final int replica) {
    final Optional<String> deployments = tryGet(replica, "/deployments");
    final Optional<String> nodes = tryGet(replica, "/nodes");
    if (deployments.isEmpty() || nodes.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          ClusterView.fromJson(
              Instant.now(),
              replica,
              Json.asArray(Json.parse(deployments.get())),
              Json.asArray(Json.parse(nodes.get()))));
    } catch (final RuntimeException e) {
      return Optional.empty();
    }
  }

  private void followLog(
      final LogWatch watch,
      final OptionalInt replicaFilter,
      final String deploymentName,
      final int instanceIndex,
      final String category,
      final String expectedText) {
    // An explicit epoch cursor, not the default: a follow with no cursor streams "from now",
    // which would silently miss anything the instance logged before this condition registered --
    // a line logged once at startup (the common case for hooks) would never satisfy it. The
    // epoch cursor replays the whole log first, then keeps tailing live.
    final String path =
        "/logs/instances/"
            + deploymentName
            + "/"
            + instanceIndex
            + "?category="
            + category
            + "&follow=true&cursor=1970-01-01T00:00:00Z";
    long attempt = 0;
    while (!watch.future().isDone() && running) {
      final int replica = replicaFilter.orElse((int) (attempt % controlPlaneBaseUrls.size()));
      attempt++;
      try {
        final HttpResponse<java.util.stream.Stream<String>> response =
            httpClient.send(
                HttpRequest.newBuilder(URI.create(controlPlaneBaseUrls.get(replica) + path))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() == 200) {
          try (java.util.stream.Stream<String> lines = response.body()) {
            final var iterator = lines.iterator();
            while (!watch.future().isDone() && iterator.hasNext()) {
              if (iterator.next().contains(expectedText)) {
                logWatches.remove(watch);
                if (watch.future().complete(null)) {
                  recordEvent("condition satisfied: " + watch.description());
                }
                return;
              }
            }
          }
        }
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (final Exception e) {
        // The stream drops while its worker restarts or the proxy hops fail over: reconnect.
      }
      if (!watch.future().isDone()) {
        try {
          Thread.sleep(500);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private Optional<String> tryGet(final int replica, final String path) {
    try {
      final HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(controlPlaneBaseUrls.get(replica) + path))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
    } catch (final Exception e) {
      return Optional.empty();
    }
  }

  // ---- forensics ----

  HolmgangConditionError timeoutError(
      final String description, final Duration waited, final Optional<String> deploymentHint) {
    return conditionError(description + " (waited " + waited + ")", deploymentHint);
  }

  HolmgangConditionError conditionError(
      final String headline, final Optional<String> deploymentHint) {
    return new HolmgangConditionError(
        ForensicReport.render(
            headline,
            Optional.ofNullable(lastView),
            processes,
            recentEventsSnapshot(),
            deploymentHint.map(this::fetchPlatformEvents).orElse(List.of()),
            workDir));
  }

  /**
   * The control plane's per-instance lifecycle event log ({@code GET
   * /events?deployment=&instance=}), folded into the report for the deployment the failed condition
   * was about -- the platform's own record of what it decided, not the harness's.
   */
  private List<String> fetchPlatformEvents(final String deploymentName) {
    final ClusterView view = lastView;
    final List<Integer> indexes = new ArrayList<>();
    if (view != null && view.deployments().containsKey(deploymentName)) {
      for (final ClusterView.InstanceView instance :
          view.deployments().get(deploymentName).instances()) {
        indexes.add(instance.instanceIndex());
      }
    }
    if (indexes.isEmpty()) {
      indexes.add(0);
    }
    final List<String> events = new ArrayList<>();
    for (final int index : indexes) {
      for (int replica = 0; replica < controlPlaneBaseUrls.size(); replica++) {
        final Optional<String> body =
            tryGet(replica, "/events?deployment=" + deploymentName + "&instance=" + index);
        if (body.isEmpty()) {
          continue;
        }
        try {
          final List<Map<String, Object>> parsed = Json.asObjectList(Json.parse(body.get()));
          for (final Map<String, Object> event :
              parsed.subList(
                  Math.max(0, parsed.size() - PLATFORM_EVENTS_PER_INSTANCE), parsed.size())) {
            events.add(
                Instant.ofEpochMilli(((Number) event.get("occurredAtEpochMilli")).longValue())
                    + " "
                    + deploymentName
                    + "#"
                    + index
                    + " "
                    + event.get("kind")
                    + ": "
                    + event.get("message"));
          }
        } catch (final RuntimeException e) {
          // A malformed event payload never blocks the report the events were meant to enrich.
        }
        break;
      }
    }
    return events;
  }

  void unregister(final InvariantGuard guard) {
    guards.remove(guard);
  }
}
