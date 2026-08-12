package com.gimle.core.time;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link ScheduledExecutorService} whose tasks fire only when a test advances virtual time, so a
 * component that paces itself from a periodic ticker can be tested without waiting for real ticks.
 *
 * <p>{@link TestClock} alone covers "what time is it now"; it does nothing for "when does the next
 * tick run", which is what a component owning a {@code scheduleAtFixedRate} loop actually depends
 * on. This closes that gap, and deliberately does so <em>sharing the same {@link TestClock}</em>:
 * one {@link #advance} moves the clock and fires the ticks due in that window together, so a task
 * reading {@code clock.instant()} sees exactly its own scheduled time. Independent clocks -- a
 * scheduler with its own internal notion of time alongside a separately-advanced {@code TestClock}
 * -- is the obvious way to build this and produces subtly wrong results the moment a periodic task
 * also reads the clock, which in this codebase they all do.
 *
 * <p>Tasks run on the thread that calls {@link #advance}/{@link #runUntilIdle}, never a background
 * one. That is the point: it makes ordering total and reproducible instead of merely likely. It
 * also sets the boundary of what this can test -- see the limitations below.
 *
 * <p><strong>Limitations, deliberate:</strong>
 *
 * <ul>
 *   <li>Because tasks run on the advancing thread, this cannot exercise genuine concurrency. Code
 *       whose correctness depends on two tasks actually overlapping needs a real executor.
 *   <li>A task that blocks waiting for another task on this same scheduler deadlocks, where a real
 *       pool would proceed. That is a true report about single-threaded execution, not a bug here.
 *   <li>{@code invokeAll}/{@code invokeAny} are unimplemented rather than approximated -- nothing
 *       in this repo schedules that way, and a plausible-looking wrong implementation is worse than
 *       an honest failure.
 * </ul>
 */
public final class TestScheduler implements ScheduledExecutorService {

  private final TestClock clock;
  private final PriorityQueue<ScheduledTask<?>> queue = new PriorityQueue<>();
  private final AtomicLong sequence = new AtomicLong();
  private volatile boolean shutdown;

  public TestScheduler(TestClock clock) {
    this.clock = clock;
  }

  /** The clock this scheduler fires against -- inject the same one into the code under test. */
  public TestClock clock() {
    return clock;
  }

  /**
   * Moves virtual time forward by {@code amount}, running every task that comes due along the way
   * in due order, and returns how many ran.
   *
   * <p>The clock is set to each task's own due time immediately before that task runs, not jumped
   * straight to the end of the window -- so a periodic task that timestamps its work, or compares
   * {@code clock.instant()} against a deadline, observes the moment it was actually scheduled for.
   * Advancing an hour past a one-second ticker therefore replays 3600 ticks at their real spacing
   * rather than one tick that believes an hour passed.
   */
  public int advance(Duration amount) {
    if (amount.isNegative()) {
      throw new IllegalArgumentException("advance() moves time forward: " + amount);
    }
    Instant target = clock.instant().plus(amount);
    int ran = 0;
    while (true) {
      ScheduledTask<?> next = nextDueBy(target);
      if (next == null) {
        break;
      }
      queue.poll();
      clock.setTo(next.dueAt);
      next.run();
      ran++;
      if (next.isPeriodic() && !next.isCancelled()) {
        next.rescheduleFrom(clock.instant());
        queue.add(next);
      }
    }
    clock.setTo(target);
    return ran;
  }

  /**
   * Runs everything already due at the current instant without moving time -- the drain for tasks
   * submitted via {@link #execute}/{@link #submit}, or scheduled with zero delay.
   */
  public int runUntilIdle() {
    return advance(Duration.ZERO);
  }

  /**
   * How many tasks are still scheduled (cancelled ones excluded), for assertions about teardown.
   */
  public int pendingTaskCount() {
    queue.removeIf(ScheduledTask::isCancelled);
    return queue.size();
  }

  private ScheduledTask<?> nextDueBy(Instant target) {
    while (true) {
      ScheduledTask<?> head = queue.peek();
      if (head == null) {
        return null;
      }
      if (head.isCancelled()) {
        queue.poll();
        continue;
      }
      return head.dueAt.isAfter(target) ? null : head;
    }
  }

  // ---- ScheduledExecutorService ----

  @Override
  public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
    return add(new ScheduledTask<>(command, null, dueAfter(delay, unit), null, false));
  }

  @Override
  public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
    return add(new ScheduledTask<>(null, callable, dueAfter(delay, unit), null, false));
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(
      Runnable command, long initialDelay, long period, TimeUnit unit) {
    return add(
        new ScheduledTask<>(
            command,
            null,
            dueAfter(initialDelay, unit),
            Duration.ofNanos(unit.toNanos(period)),
            true));
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(
      Runnable command, long initialDelay, long delay, TimeUnit unit) {
    return add(
        new ScheduledTask<>(
            command,
            null,
            dueAfter(initialDelay, unit),
            Duration.ofNanos(unit.toNanos(delay)),
            false));
  }

  @Override
  public void execute(Runnable command) {
    schedule(command, 0, TimeUnit.NANOSECONDS);
  }

  @Override
  public Future<?> submit(Runnable task) {
    return schedule(task, 0, TimeUnit.NANOSECONDS);
  }

  @Override
  public <T> Future<T> submit(Runnable task, T result) {
    return schedule(
        () -> {
          task.run();
          return result;
        },
        0,
        TimeUnit.NANOSECONDS);
  }

  @Override
  public <T> Future<T> submit(Callable<T> task) {
    return schedule(task, 0, TimeUnit.NANOSECONDS);
  }

  @Override
  public void shutdown() {
    shutdown = true;
  }

  @Override
  public List<Runnable> shutdownNow() {
    shutdown = true;
    queue.clear();
    return List.of();
  }

  @Override
  public boolean isShutdown() {
    return shutdown;
  }

  @Override
  public boolean isTerminated() {
    return shutdown && queue.isEmpty();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) {
    return isTerminated();
  }

  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
    throw new UnsupportedOperationException(unsupported("invokeAll"));
  }

  @Override
  public <T> List<Future<T>> invokeAll(
      Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
    throw new UnsupportedOperationException(unsupported("invokeAll"));
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
    throw new UnsupportedOperationException(unsupported("invokeAny"));
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
    throw new UnsupportedOperationException(unsupported("invokeAny"));
  }

  private static String unsupported(String method) {
    return method
        + " is not implemented by TestScheduler -- nothing in this repo schedules that way, and"
        + " approximating its semantics on a single-threaded scheduler would be misleading. Use a"
        + " real executor for code that needs it.";
  }

  private Instant dueAfter(long delay, TimeUnit unit) {
    return clock.instant().plusNanos(unit.toNanos(delay));
  }

  private <V> ScheduledTask<V> add(ScheduledTask<V> task) {
    queue.add(task);
    return task;
  }

  /**
   * Comparable by due time, then by submission order so same-instant tasks fire in the order they
   * were scheduled rather than an arbitrary heap order -- without the tiebreak, two ticks due at
   * the same virtual instant would run in an order that varies between runs, reintroducing exactly
   * the nondeterminism this class exists to remove.
   */
  private final class ScheduledTask<V> extends FutureTask<V>
      implements ScheduledFuture<V>, Comparable<Delayed> {

    private final long order = sequence.getAndIncrement();
    private final Duration repeatEvery;
    private final boolean fixedRate;
    private Instant dueAt;

    private ScheduledTask(
        Runnable runnable,
        Callable<V> callable,
        Instant dueAt,
        Duration repeatEvery,
        boolean fixedRate) {
      super(callable != null ? callable : java.util.concurrent.Executors.callable(runnable, null));
      this.dueAt = dueAt;
      this.repeatEvery = repeatEvery;
      this.fixedRate = fixedRate;
    }

    boolean isPeriodic() {
      return repeatEvery != null;
    }

    /**
     * Moves this task's next due time; the caller has already run it. Fixed-rate repeats from the
     * tick's own scheduled time, fixed-delay from when it finished -- matching {@link
     * ScheduledExecutorService}'s own distinction, which matters here because a fixed-rate ticker
     * must not drift just because a tick's body advanced the clock.
     */
    void rescheduleFrom(Instant finishedAt) {
      dueAt = (fixedRate ? dueAt : finishedAt).plus(repeatEvery);
    }

    /**
     * {@code runAndReset} rather than {@code super.run()} for a periodic task: {@link FutureTask}
     * latches into a completed state after a normal run and would silently refuse every subsequent
     * tick, so a ticker would fire exactly once and then go quiet. Resetting leaves it runnable for
     * the next due time. A one-shot task keeps the latching behavior, which is what makes its
     * {@code Future} readable afterwards.
     */
    @Override
    public void run() {
      if (isPeriodic()) {
        runAndReset();
      } else {
        super.run();
      }
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return unit.convert(Duration.between(clock.instant(), dueAt));
    }

    @Override
    public int compareTo(Delayed other) {
      if (other instanceof TestScheduler.ScheduledTask<?> task) {
        int byTime = dueAt.compareTo(task.dueAt);
        return byTime != 0 ? byTime : Long.compare(order, task.order);
      }
      return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
    }
  }

  /** Convenience for a caller that wants the value a scheduled {@link Callable} produced. */
  public static <V> V resultOf(Future<V> future) {
    try {
      return future.get(0, TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted reading an already-completed task's result", e);
    } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
      throw new IllegalStateException(
          "task had not completed -- advance() past its due time first", e);
    }
  }

  /**
   * All still-pending due times, earliest first, for asserting on scheduling rather than effects.
   */
  public List<Instant> pendingDueTimes() {
    List<ScheduledTask<?>> snapshot = new ArrayList<>(queue);
    snapshot.removeIf(ScheduledTask::isCancelled);
    snapshot.sort(null);
    return snapshot.stream().map(task -> task.dueAt).toList();
  }
}
