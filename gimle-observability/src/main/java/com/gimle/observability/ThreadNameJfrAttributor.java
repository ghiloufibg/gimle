package com.gimle.observability;

import com.gimle.core.module.ModuleInstanceId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

/**
 * Attributes JFR {@code jdk.ExecutionSample}/{@code jdk.ObjectAllocationSample} events to modules
 * by thread-name prefix ({@code gimle-<module>-<version>-}) -- a different classification key than
 * the module system's classloader-package heuristic used elsewhere, because the question here is
 * "whose <em>work</em> is this," not "whose <em>classes</em> are these." Memoizes the
 * thread-name-to-prefix classification (a virtual thread's name never changes after creation), so
 * repeated samples for the same thread don't re-scan the live prefix set every time.
 *
 * <p>Both events are sampling-based (not the older periodic {@code jdk.ThreadAllocationStatistics},
 * which was tried first and rejected: it walks only the JVM's live platform-thread list, so it
 * never once names a virtual thread -- confirmed against this JDK's actual event stream -- and
 * every module-hosting thread here is virtual). {@code jdk.ObjectAllocationSample}'s own {@code
 * eventThread} field is virtual-thread-aware, matching {@code jdk.ExecutionSample}'s {@code
 * sampledThread} field; {@code jdk.ExecutionSample} in particular does <em>not</em> use the generic
 * {@code eventThread} convention {@link RecordedEvent#getThread()} would read, so its field name
 * was confirmed against this JDK's actual {@code EventType} metadata rather than assumed.
 */
public final class ThreadNameJfrAttributor implements AutoCloseable {

  // jdk.ExecutionSample's own built-in default period is "everyChunk" -- effectively never, for a
  // RecordingStream that (like this one) is never configured with a maxAge/maxSize that would force
  // periodic chunk rotation. An explicit period is required or no sample ever fires, regardless of
  // how much CPU a classified thread burns. jdk.ObjectAllocationSample needs no such override -- as
  // a throttled allocation-site sampler (not a periodic thread-list walk), it fires on its own as
  // allocation happens.
  private static final Duration EXECUTION_SAMPLE_PERIOD = Duration.ofMillis(20);

  private final MeterRegistry registry;
  private final Set<String> livePrefixes = ConcurrentHashMap.newKeySet();
  private final Map<String, String> threadNameToPrefix = new ConcurrentHashMap<>();
  private final RecordingStream stream;

  public ThreadNameJfrAttributor(MeterRegistry registry) {
    this.registry = registry;
    RecordingStream started;
    try {
      started = new RecordingStream();
      started.enable("jdk.ExecutionSample").withPeriod(EXECUTION_SAMPLE_PERIOD);
      started.enable("jdk.ObjectAllocationSample");
      started.onEvent("jdk.ExecutionSample", this::onExecutionSample);
      started.onEvent("jdk.ObjectAllocationSample", this::onAllocationSample);
      started.startAsync();
    } catch (RuntimeException e) {
      // JFR unavailable/disabled in this environment: attribution degrades to "no samples,"
      // never fails the worker over it -- same degrade-don't-fail posture this codebase uses
      // elsewhere for JFR-dependent instrumentation.
      started = null;
    }
    this.stream = started;
  }

  public void registerModule(ModuleInstanceId id) {
    livePrefixes.add(prefixFor(id));
  }

  public void unregisterModule(ModuleInstanceId id) {
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

  /**
   * {@code weight} is the sampler's own estimate of bytes allocated at this sample -- not an exact
   * count the way a TLAB-level counter would be, the standard tradeoff every low-overhead
   * allocation profiler (this JDK's own {@code jdk.ObjectAllocationSample} included) makes to avoid
   * instrumenting every single allocation.
   */
  private void onAllocationSample(RecordedEvent event) {
    Optional<String> prefix = classify(event.getThread());
    if (prefix.isEmpty() || !event.hasField("weight")) {
      return;
    }
    Counter.builder("gimle.module.allocated.bytes")
        .tag("module_prefix", prefix.get())
        .register(registry)
        .increment(event.getLong("weight"));
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

  private static String prefixFor(ModuleInstanceId id) {
    return "gimle-" + id.name() + "-" + id.version() + "-";
  }

  @Override
  public void close() {
    if (stream != null) {
      stream.close();
    }
  }
}
