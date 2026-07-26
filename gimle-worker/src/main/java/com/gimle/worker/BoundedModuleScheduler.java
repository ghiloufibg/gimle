package com.gimle.worker;

import com.gimle.core.module.ModuleId;
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
    return executor.submit(
        () -> {
          concurrencyBound.acquire();
          try {
            return task.call();
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
