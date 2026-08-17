package com.gimle.module.leak;

import com.gimle.core.module.ModuleId;
import com.gimle.module.layer.ModuleLayerHandle;
import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classloader leak detection: after a module is disposed, holds a {@link PhantomReference} to its
 * loader and reports a leak if it survives {@code window}. A background virtual thread drains the
 * reference queue continuously (removing any loader the GC actually reclaimed); a periodic sweep
 * escalates entries that are both past their window <em>and</em> still tracked — triggering one
 * {@code System.gc()} first, since the sweep interval ({@code window / 2}) gives the drain thread
 * at least one more full poll to catch a collection that was merely pending.
 */
public final class LeakTracker implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(LeakTracker.class);

  private final Duration window;
  private final Consumer<ModuleLeakDetected> onLeakDetected;
  private final ReferenceQueue<ClassLoader> referenceQueue = new ReferenceQueue<>();
  private final Map<PhantomReference<ClassLoader>, LeakRecord> tracked = new ConcurrentHashMap<>();
  private final OldObjectSampleCorrelator correlator = new OldObjectSampleCorrelator();
  private final Thread queueDrainThread;
  private final ScheduledExecutorService sweepExecutor;
  private volatile boolean closed;

  public LeakTracker(Duration window, Consumer<ModuleLeakDetected> onLeakDetected) {
    this.window = window;
    this.onLeakDetected = onLeakDetected;

    this.queueDrainThread =
        Thread.ofVirtual().name("gimle-leak-tracker-queue").unstarted(this::drainQueueLoop);
    queueDrainThread.start();

    this.sweepExecutor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "gimle-leak-tracker-sweep");
              t.setDaemon(true);
              return t;
            });
    long sweepIntervalMillis = Math.max(10, window.dividedBy(2).toMillis());
    sweepExecutor.scheduleWithFixedDelay(
        this::sweep, sweepIntervalMillis, sweepIntervalMillis, TimeUnit.MILLISECONDS);
  }

  public void track(ModuleId id, ModuleLayerHandle handle) {
    Set<String> packages =
        handle.layer().modules().stream()
            .flatMap(m -> m.getPackages().stream())
            .collect(Collectors.toUnmodifiableSet());
    PhantomReference<ClassLoader> ref = new PhantomReference<>(handle.loader(), referenceQueue);
    tracked.put(ref, new LeakRecord(id, Instant.now(), packages));
  }

  private void drainQueueLoop() {
    while (!closed) {
      try {
        Reference<? extends ClassLoader> ref = referenceQueue.remove(200);
        if (ref != null) {
          tracked.remove(ref);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void sweep() {
    System.gc();
    Instant now = Instant.now();
    for (var entry : List.copyOf(tracked.entrySet())) {
      LeakRecord record = entry.getValue();
      if (Duration.between(record.undeployedAt(), now).compareTo(window) < 0) {
        continue; // not old enough yet
      }
      if (tracked.remove(entry.getKey()) != null) {
        Duration survival = Duration.between(record.undeployedAt(), Instant.now());
        Optional<String> retainingPath = correlator.findRetainingPath(record.packages());
        try {
          onLeakDetected.accept(new ModuleLeakDetected(record.id(), survival, retainingPath));
        } catch (RuntimeException e) {
          log.warn("leak-detected callback threw for module {}", record.id(), e);
        }
      }
    }
  }

  @Override
  public void close() {
    closed = true;
    queueDrainThread.interrupt();
    sweepExecutor.shutdownNow();
    correlator.close();
  }

  private record LeakRecord(ModuleId id, Instant undeployedAt, Set<String> packages) {}
}
