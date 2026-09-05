package com.gimle.worker;

import com.gimle.core.logging.InstanceMdcContext;
import com.gimle.core.module.ModuleId;
import com.gimle.fabric.transport.ModuleWorkExecutor;
import io.opentelemetry.context.Context;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * A per-module, bounded virtual-thread scheduler: submitted tasks queue behind a {@link Semaphore}
 * rather than letting an unbounded number run concurrently, while still creating a (cheap) virtual
 * thread per task rather than pooling platform threads. Every thread is named {@code
 * gimle-<module-name>-<version>-N} -- the naming JFR thread-name attribution keys off.
 *
 * <p>{@link #submit} captures the caller's current OpenTelemetry {@link Context} and restores it
 * for the task's duration on its own (fresh) virtual thread -- without this, a span started before
 * dispatching work onto this scheduler would otherwise not be the parent of whatever span the task
 * itself starts, since {@code Context} is thread-scoped and a new virtual thread starts with none.
 */
public final class BoundedModuleScheduler implements AutoCloseable {

  private final Semaphore concurrencyBound;
  private final ExecutorService executor;
  private final Map<String, String> mdcTags;

  public BoundedModuleScheduler(ModuleId id, int maxConcurrency) {
    this(id, maxConcurrency, Map.of());
  }

  /**
   * {@code mdcTags} tags every probe-check log line this scheduler dispatches as this instance's
   * own -- empty for a caller that hasn't wired instance identity through yet, in which case such
   * lines fall back to PLATFORM (see {@code InstanceMdcContext}/{@code JsonLogEncoder}).
   */
  public BoundedModuleScheduler(ModuleId id, int maxConcurrency, Map<String, String> mdcTags) {
    if (maxConcurrency < 1) {
      throw new IllegalArgumentException("maxConcurrency must be at least 1: " + maxConcurrency);
    }
    this.mdcTags = mdcTags;
    this.concurrencyBound = new Semaphore(maxConcurrency);
    ThreadFactory factory =
        Thread.ofVirtual().name("gimle-" + id.name() + "-" + id.version() + "-", 0).factory();
    this.executor = Executors.newThreadPerTaskExecutor(factory);
  }

  /**
   * An estimate of how many submitted tasks are currently blocked waiting for a concurrency permit,
   * not yet running -- {@link Semaphore#getQueueLength()}'s own "best effort, not a point-in-time
   * guarantee under contention" caveat applies, which is fine for a periodic metrics signal (feeds
   * {@code MetricsReport#queueDepth}) rather than a correctness-critical read.
   */
  public int queuedCount() {
    return concurrencyBound.getQueueLength();
  }

  /**
   * This scheduler as the inbound-call executor {@code FabricServer} runs work through, including
   * the {@link #queuedCount()} reading it reports back to callers so their load balancing sees this
   * module's real backlog rather than only their own in-flight requests to it.
   */
  public ModuleWorkExecutor asWorkExecutor() {
    return new ModuleWorkExecutor() {
      @Override
      public <T> Future<T> submit(Callable<T> task) {
        return BoundedModuleScheduler.this.submit(task);
      }

      @Override
      public int queueDepth() {
        return queuedCount();
      }
    };
  }

  public <T> Future<T> submit(Callable<T> task) {
    Callable<T> withCallerContext = Context.current().wrap(task);
    Callable<T> tagged =
        mdcTags.isEmpty()
            ? withCallerContext
            : () -> InstanceMdcContext.runTagged(mdcTags, withCallerContext);
    return executor.submit(
        () -> {
          concurrencyBound.acquire();
          try {
            return tagged.call();
          } finally {
            concurrencyBound.release();
          }
        });
  }

  @Override
  public void close() {
    // By the time a caller reaches close(), its own bounded drain deadline has already elapsed
    // (see ModuleController.stop's drainTimeout) -- waiting indefinitely for in-flight tasks here,
    // as executor.close() does, would let one hung module task wedge the worker's whole control
    // loop forever. Interrupt in-flight tasks and cap the wait instead.
    executor.shutdownNow();
    try {
      executor.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
