package com.gimle.observability;

import com.gimle.core.module.ModuleId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

/**
 * Attributes JFR {@code jdk.ExecutionSample}/{@code jdk.ThreadAllocationStatistics} events to
 * modules by thread-name prefix ({@code gimle-<module>-<version>-}), per design §5.2 — a different
 * key than Phase 1's {@code LeakTracker} package-heuristic, because the question here is "whose
 * <em>work</em> is this," not "whose <em>classes</em> are these." Memoizes the
 * thread-name-to-prefix classification (a virtual thread's name never changes after creation), so
 * repeated samples for the same thread don't re-scan the live prefix set every time.
 *
 * <p>Field names below ({@code sampledThread}, {@code thread}) were confirmed against this JDK's
 * actual {@code EventType} metadata rather than assumed — {@code jdk.ExecutionSample} in particular
 * does <em>not</em> use the generic {@code eventThread} convention {@link
 * RecordedEvent#getThread()} would read; its field is explicitly {@code sampledThread}.
 */
public final class ThreadNameJfrAttributor implements AutoCloseable {

  private final MeterRegistry registry;
  private final Set<String> livePrefixes = ConcurrentHashMap.newKeySet();
  private final Map<String, String> threadNameToPrefix = new ConcurrentHashMap<>();
  private final RecordingStream stream;

  public ThreadNameJfrAttributor(MeterRegistry registry) {
    this.registry = registry;
    RecordingStream started;
    try {
      started = new RecordingStream();
      started.enable("jdk.ExecutionSample");
      started.enable("jdk.ThreadAllocationStatistics");
      started.onEvent("jdk.ExecutionSample", this::onExecutionSample);
      started.onEvent("jdk.ThreadAllocationStatistics", this::onAllocationSample);
      started.startAsync();
    } catch (RuntimeException e) {
      // JFR unavailable/disabled in this environment: attribution degrades to "no samples,"
      // never fails the worker over it -- same posture as Phase 1's LeakTracker correlator.
      started = null;
    }
    this.stream = started;
  }

  public void registerModule(ModuleId id) {
    livePrefixes.add(prefixFor(id));
  }

  public void unregisterModule(ModuleId id) {
    String prefix = prefixFor(id);
    livePrefixes.remove(prefix);
    threadNameToPrefix.values().removeIf(prefix::equals);
  }

  private void onExecutionSample(RecordedEvent event) {
    RecordedThread thread = event.getThread("sampledThread");
    classify(thread)
        .ifPresent(
            prefix ->
                Counter.builder("gimle.module.cpu.samples")
                    .tag("module_prefix", prefix)
                    .register(registry)
                    .increment());
  }

  private void onAllocationSample(RecordedEvent event) {
    RecordedThread thread = event.getThread("thread");
    Optional<String> prefix = classify(thread);
    if (prefix.isEmpty() || !event.hasField("allocated")) {
      return;
    }
    Counter.builder("gimle.module.allocated.bytes")
        .tag("module_prefix", prefix.get())
        .register(registry)
        .increment(event.getLong("allocated"));
  }

  private Optional<String> classify(RecordedThread thread) {
    if (thread == null) {
      return Optional.empty();
    }
    String name = thread.getJavaName();
    if (name == null) {
      return Optional.empty();
    }
    String cached = threadNameToPrefix.get(name);
    if (cached != null) {
      return Optional.of(cached);
    }
    for (String prefix : livePrefixes) {
      if (name.startsWith(prefix)) {
        threadNameToPrefix.put(name, prefix);
        return Optional.of(prefix);
      }
    }
    return Optional.empty();
  }

  private static String prefixFor(ModuleId id) {
    return "gimle-" + id.name() + "-" + id.version() + "-";
  }

  @Override
  public void close() {
    if (stream != null) {
      stream.close();
    }
  }
}
