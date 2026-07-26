package com.gimle.worker;

import com.gimle.core.module.ModuleId;
import io.opentelemetry.context.Context;
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
 * gimle-<module-name>-<version>-N} — the naming the JFR thread-name attribution (§5.2 of the
 * design) keys off.
 *
 * <p>{@link #submit} captures the caller's current OpenTelemetry {@link Context} and restores it
 * for the task's duration on its own (fresh) virtual thread (Phase 4 §11: "propagated... into
 * virtual threads spawned via structured concurrency, with zero fabric involvement") -- without
 * this, a span started before dispatching work onto this scheduler would otherwise not be the
 * parent of whatever span the task itself starts, since {@code Context} is thread-scoped and a new
 * virtual thread starts with none.
 */
public final class BoundedModuleScheduler implements AutoCloseable {

  private final Semaphore concurrencyBound;
  private final ExecutorService executor;

  public BoundedModuleScheduler(ModuleId id, int maxConcurrency) {
    if (maxConcurrency < 1) {
      throw new IllegalArgumentException("maxConcurrency must be at least 1: " + maxConcurrency);
    }
    this.concurrencyBound = new Semaphore(maxConcurrency);
    ThreadFactory factory =
        Thread.ofVirtual().name("gimle-" + id.name() + "-" + id.version() + "-", 0).factory();
    this.executor = Executors.newThreadPerTaskExecutor(factory);
  }

  public <T> Future<T> submit(Callable<T> task) {
    Callable<T> withCallerContext = Context.current().wrap(task);
    return executor.submit(
        () -> {
          concurrencyBound.acquire();
          try {
            return withCallerContext.call();
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
