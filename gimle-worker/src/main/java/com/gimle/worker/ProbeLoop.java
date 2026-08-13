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

  private final ScheduledExecutorService ticker;
  private final Map<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

  public ProbeLoop() {
    this(
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-probe-loop-ticker").unstarted(r)));
  }

  /**
   * Accepts the ticker rather than always owning one, so a test can supply a deterministic
   * scheduler and drive ticks by advancing virtual time instead of sleeping past real ones -- see
   * {@code TestScheduler} in {@code gimle-core}'s test-jar. Production uses the no-arg constructor
   * above and its own virtual-thread ticker, unchanged.
   *
   * <p>Note this virtualizes only tick *scheduling*. Each tick still runs its check on the module's
   * own {@link BoundedModuleScheduler} and bounds it with a real {@code Future#get} timeout, which
   * is deliberate: that timeout is the thing protecting the platform from a hung probe, and a test
   * that wants to prove it fires has to let it fire for real.
   */
  public ProbeLoop(ScheduledExecutorService ticker) {
    this.ticker = ticker;
  }

  /** Back-compat: first tick fires one {@code interval} after this call, with no initial delay. */
  public void start(
      String key,
      BoundedModuleScheduler moduleScheduler,
      Callable<Boolean> check,
      Duration interval,
      Duration timeout,
      Consumer<Boolean> onResult) {
    start(key, moduleScheduler, check, interval, timeout, interval, onResult);
  }

  /**
   * {@code initialDelay} is the delay before the *first* tick, independent of {@code interval} --
   * lets a module declare its own post-{@code onStart} warmup window (lazy init, cache fill, JIT)
   * without shrinking every subsequent tick's spacing to match. A module with no declared {@code
   * initialDelaySeconds} gets the no-initial-delay behavior via the shorter overload above, which
   * passes {@code interval} here unchanged.
   */
  public void start(
      String key,
      BoundedModuleScheduler moduleScheduler,
      Callable<Boolean> check,
      Duration interval,
      Duration timeout,
      Duration initialDelay,
      Consumer<Boolean> onResult) {
    Runnable tick = () -> runOneTick(moduleScheduler, check, timeout, onResult);
    ScheduledFuture<?> handle =
        ticker.scheduleAtFixedRate(
            tick, initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
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

  private void runOneTick(
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
