package com.gimle.worker;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Periodically invokes a bounded check (a module's liveness or readiness probe) and reports
 * pass/fail to a callback. The check itself runs on the caller-supplied {@link
 * BoundedModuleScheduler} — so a hung probe consumes that module's own concurrency budget, never a
 * shared platform one — with a hard timeout; a timeout or thrown exception counts as a failed check
 * and never propagates out of the loop. One shared ticker thread schedules ticks for every
 * registered check; the checks themselves run on their own module's scheduler, not this thread.
 */
public final class ProbeLoop implements AutoCloseable {

  private final ScheduledExecutorService ticker =
      Executors.newSingleThreadScheduledExecutor(
          r -> Thread.ofVirtual().name("gimle-probe-loop-ticker").unstarted(r));
  private final Map<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

  public void start(
      String key,
      BoundedModuleScheduler moduleScheduler,
      Callable<Boolean> check,
      Duration interval,
      Duration timeout,
      Consumer<Boolean> onResult) {
    Runnable tick = () -> run_one_tick(moduleScheduler, check, timeout, onResult);
    ScheduledFuture<?> handle =
        ticker.scheduleAtFixedRate(
            tick, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    ScheduledFuture<?> previous = scheduled.put(key, handle);
    if (previous != null) {
      previous.cancel(true);
    }
  }

  public void stop(String key) {
    ScheduledFuture<?> handle = scheduled.remove(key);
    if (handle != null) {
      handle.cancel(true);
    }
  }

  private void run_one_tick(
      BoundedModuleScheduler moduleScheduler,
      Callable<Boolean> check,
      Duration timeout,
      Consumer<Boolean> onResult) {
    Future<Boolean> future = moduleScheduler.submit(check);
    boolean result;
    try {
      result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    } catch (ExecutionException | TimeoutException e) {
      result = false;
    } finally {
      future.cancel(true);
    }
    onResult.accept(result);
  }

  @Override
  public void close() {
    scheduled.values().forEach(future -> future.cancel(true));
    scheduled.clear();
    ticker.shutdownNow();
  }
}
