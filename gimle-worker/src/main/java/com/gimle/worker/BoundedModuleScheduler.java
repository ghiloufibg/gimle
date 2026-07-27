package com.gimle.worker;

import com.gimle.core.logging.InstanceMdcContext;
import com.gimle.core.module.ModuleId;
import io.opentelemetry.context.Context;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;

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
    executor.close();
  }
}
