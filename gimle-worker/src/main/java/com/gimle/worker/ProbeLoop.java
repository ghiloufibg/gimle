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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically invokes a bounded check (a module's liveness or readiness probe) and reports
 * pass/fail to a callback. The check itself runs on the caller-supplied {@link
 * BoundedModuleScheduler} — so a hung probe consumes that module's own concurrency budget, never a
 * shared platform one — with a hard timeout; a timeout or thrown exception counts as a failed check
 * and never propagates out of the loop. In production, each registered check key gets its own
 * dedicated ticker thread rather than sharing one platform-wide pool: {@link #runOneTick}'s own
 * wait for a tick's outcome blocks whatever thread the ticker fires it on, so a shared pool would
 * let a handful of permanently-hung checks anywhere on the worker starve ticking for every other
 * module's health checks too. One key's ticker thread can only ever be pinned by that key's own
 * check, never anyone else's.
 */
public final class ProbeLoop implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ProbeLoop.class);

  // Exactly one of these two is non-null, decided once at construction (see the two constructors
  // below): perKeyTickers null means every key shares sharedTicker, perKeyTickers non-null means
  // each key gets (and owns) its own entry, created lazily in tickerFor.
  private final ScheduledExecutorService sharedTicker;
  private final Map<String, ScheduledExecutorService> perKeyTickers;
  private final Map<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

  /**
   * Production: see this class's own javadoc for why a shared pool isn't safe here. Each key's
   * ticker is a single-thread, virtual-thread-backed executor created the first time that key is
   * {@link #start started} and torn down when it's {@link #stop stopped}.
   */
  public ProbeLoop() {
    this.sharedTicker = null;
    this.perKeyTickers = new ConcurrentHashMap<>();
  }

  /**
   * Accepts one ticker shared by every registered key, rather than production's one-per-key
   * default above, so a test can supply a deterministic scheduler and drive every key's ticks by
   * advancing virtual time on a single instance instead of sleeping past real ones -- see {@code
   * TestScheduler} in {@code gimle-core}'s test-jar. Production uses the no-arg constructor above
   * and its own per-key virtual-thread tickers, unchanged.
   *
   * <p>Note this virtualizes only tick *scheduling*. Each tick still runs its check on the module's
   * own {@link BoundedModuleScheduler} and bounds it with a real {@code Future#get} timeout, which
   * is deliberate: that timeout is the thing protecting the platform from a hung probe, and a test
   * that wants to prove it fires has to let it fire for real.
   */
  public ProbeLoop(ScheduledExecutorService ticker) {
    this.sharedTicker = ticker;
    this.perKeyTickers = null;
  }

  private ScheduledExecutorService tickerFor(String key) {
    if (perKeyTickers == null) {
      return sharedTicker;
    }
    return perKeyTickers.computeIfAbsent(
        key,
        k ->
            Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofVirtual().name("gimle-probe-loop-ticker-" + k).unstarted(r)));
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
    Runnable tick = () -> runOneTick(key, moduleScheduler, check, timeout, onResult);
    ScheduledFuture<?> handle =
        tickerFor(key)
            .scheduleAtFixedRate(
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
    if (perKeyTickers != null) {
      ScheduledExecutorService ownTicker = perKeyTickers.remove(key);
      if (ownTicker != null) {
        ownTicker.shutdownNow();
      }
    }
  }

  private void runOneTick(
      String key,
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
    try {
      onResult.accept(result);
    } catch (RuntimeException e) {
      log.warn("probe result callback for {} threw", key, e);
    }
  }

  @Override
  public void close() {
    scheduled.values().forEach(future -> future.cancel(true));
    scheduled.clear();
    if (perKeyTickers != null) {
      perKeyTickers.values().forEach(ScheduledExecutorService::shutdownNow);
      perKeyTickers.clear();
    } else {
      sharedTicker.shutdownNow();
    }
  }
}
