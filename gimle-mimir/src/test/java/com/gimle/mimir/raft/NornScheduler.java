package com.gimle.mimir.raft;

import com.gimle.core.time.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link ScheduledExecutorService} over a shared {@link TestClock}, built for exactly one thing
 * {@code com.gimle.core.time.TestScheduler} is not: safe concurrent use by several {@link RaftNode}
 * instances' own real background threads (registering new callbacks via {@code schedule}) while a
 * single driving thread ({@link NornCluster#advanceVirtualTime}) decides execution order.
 *
 * <p>{@code TestScheduler}'s own javadoc is explicit that its internal queue assumes
 * single-threaded use ("tasks run on the thread that calls advance... this cannot exercise genuine
 * concurrency"), and {@link NornCluster} genuinely violates that: a node's {@code AppendEntries}
 * handler can call {@code resetElectionTimerLocked()} -> {@code scheduler.schedule(...)} on a real
 * background thread at the same moment the driving thread is mid-{@code advance()} on that same
 * instance, corrupting the shared {@code PriorityQueue} heap. A single lock around both paths was
 * considered and rejected: the driving thread would hold it while running a due callback that needs
 * {@code RaftNode.lock}, while a different node's background thread can hold {@code RaftNode.lock}
 * (inside {@code onAppendEntries}) and need that same scheduler lock to reset its election timer --
 * a real AB-BA deadlock, not a theoretical one, since receiving {@code AppendEntries} and resetting
 * the timer is normal traffic.
 *
 * <p>The fix is to never share a lock between the two paths at all. Registration ({@link #schedule}
 * and friends, called freely by any thread) only ever appends to {@link #incoming}, a {@link
 * ConcurrentLinkedQueue} -- lock-free, never blocks, never touches the execution-ordering
 * structure. Execution ordering ({@link #advance}, {@link #pendingDueTimes}) is owned exclusively
 * by whichever thread calls them -- by contract, always {@link NornCluster}'s single driving thread
 * -- and drains {@link #incoming} into a private {@link PriorityQueue} at the start of each call
 * before doing anything else. No method here ever blocks waiting on another thread, so there is
 * nothing left to deadlock against.
 */
final class NornScheduler implements ScheduledExecutorService {

  private final TestClock clock;
  private final Queue<Task<?>> incoming = new ConcurrentLinkedQueue<>();
  private final PriorityQueue<Task<?>> queue = new PriorityQueue<>();
  private final AtomicLong sequence = new AtomicLong();
  private volatile boolean shutdown;

  NornScheduler(TestClock clock) {
    this.clock = clock;
  }

  /** Drains everything registered since the last call into the execution-ordering queue. */
  private void drainIncoming() {
    Task<?> task;
    while ((task = incoming.poll()) != null) {
      queue.add(task);
    }
  }

  /**
   * Moves virtual time forward by {@code amount}, running every task that comes due along the way
   * in due order, and returns how many ran. Mirrors {@code TestScheduler#advance} exactly -- see
   * its javadoc for why the clock is set to each task's own due time rather than jumped to the
   * window's end.
   */
  int advance(Duration amount) {
    if (amount.isNegative()) {
      throw new IllegalArgumentException("advance() moves time forward: " + amount);
    }
    Instant target = clock.instant().plus(amount);
    int ran = 0;
    while (true) {
      drainIncoming();
      Task<?> next = nextDueBy(target);
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

  private Task<?> nextDueBy(Instant target) {
    while (true) {
      Task<?> head = queue.peek();
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

  /** All still-pending due times, earliest first -- draining {@link #incoming} first. */
  List<Instant> pendingDueTimes() {
    drainIncoming();
    List<Task<?>> snapshot = new ArrayList<>(queue);
    snapshot.removeIf(Task::isCancelled);
    snapshot.sort(null);
    return snapshot.stream().map(task -> task.dueAt).toList();
  }

  // ---- ScheduledExecutorService ----

  @Override
  public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
    return add(new Task<>(command, null, dueAfter(delay, unit), null, false));
  }

  @Override
  public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
    return add(new Task<>(null, callable, dueAfter(delay, unit), null, false));
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(
      Runnable command, long initialDelay, long period, TimeUnit unit) {
    return add(
        new Task<>(
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
        new Task<>(
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
    incoming.clear();
    queue.clear();
    return List.of();
  }

  @Override
  public boolean isShutdown() {
    return shutdown;
  }

  @Override
  public boolean isTerminated() {
    return shutdown && incoming.isEmpty() && queue.isEmpty();
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
        + " is not implemented by NornScheduler -- nothing in this codebase schedules that way, and"
        + " approximating its semantics here would be misleading. Use a real executor for code that"
        + " needs it.";
  }

  private Instant dueAfter(long delay, TimeUnit unit) {
    return clock.instant().plusNanos(unit.toNanos(delay));
  }

  /**
   * Registration only ever appends to {@link #incoming} -- never touches {@link #queue} directly,
   * which is what makes this safe to call from any thread at any time (see class javadoc).
   */
  private <V> Task<V> add(Task<V> task) {
    incoming.add(task);
    return task;
  }

  /**
   * Comparable by due time, then by submission order so same-instant tasks fire in the order they
   * were scheduled rather than an arbitrary heap order -- identical tiebreak to {@code
   * TestScheduler.ScheduledTask}, for the same reason.
   */
  private final class Task<V> extends FutureTask<V>
      implements ScheduledFuture<V>, Comparable<Delayed> {

    private final long order = sequence.getAndIncrement();
    private final Duration repeatEvery;
    private final boolean fixedRate;
    private Instant dueAt;

    private Task(
        Runnable runnable,
        Callable<V> callable,
        Instant dueAt,
        Duration repeatEvery,
        boolean fixedRate) {
      super(callable != null ? callable : Executors.callable(runnable, null));
      this.dueAt = dueAt;
      this.repeatEvery = repeatEvery;
      this.fixedRate = fixedRate;
    }

    boolean isPeriodic() {
      return repeatEvery != null;
    }

    /** Same fixed-rate-vs-fixed-delay distinction as {@code TestScheduler.ScheduledTask}. */
    void rescheduleFrom(Instant finishedAt) {
      dueAt = (fixedRate ? dueAt : finishedAt).plus(repeatEvery);
    }

    /**
     * Same {@code runAndReset} vs {@code super.run()} distinction as {@code
     * TestScheduler.ScheduledTask}.
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
      if (other instanceof NornScheduler.Task<?> task) {
        int byTime = dueAt.compareTo(task.dueAt);
        return byTime != 0 ? byTime : Long.compare(order, task.order);
      }
      return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
    }
  }
}
