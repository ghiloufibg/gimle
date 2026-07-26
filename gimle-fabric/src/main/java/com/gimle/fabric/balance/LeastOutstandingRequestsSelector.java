package com.gimle.fabric.balance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Least-outstanding-requests selection among a candidate set, ties broken round-robin (Phase 4 §8)
 * -- the algorithm {@code SimpleServiceRegistry}'s own Javadoc named two phases in advance as "the
 * right algorithm once a call crosses a real network boundary with its own dispatch layer to
 * instrument." That dispatch layer is the proxy's {@code InvocationHandler}, which is exactly where
 * {@link #begin}/{@link #end} get called around each cross-hop invocation -- outstanding counts
 * genuinely can't be tracked without a dispatch layer to instrument, which same-worker calls
 * deliberately don't have.
 */
public final class LeastOutstandingRequestsSelector<E> {

  private final Map<E, AtomicInteger> outstanding = new ConcurrentHashMap<>();
  private final AtomicInteger roundRobinCursor = new AtomicInteger();

  public E select(List<E> candidates) {
    if (candidates.isEmpty()) {
      throw new NoSuchElementException("no candidates to select from");
    }
    int minOutstanding = Integer.MAX_VALUE;
    List<E> tied = new ArrayList<>();
    for (E candidate : candidates) {
      int count = outstanding.computeIfAbsent(candidate, key -> new AtomicInteger()).get();
      if (count < minOutstanding) {
        minOutstanding = count;
        tied.clear();
        tied.add(candidate);
      } else if (count == minOutstanding) {
        tied.add(candidate);
      }
    }
    int index = Math.floorMod(roundRobinCursor.getAndIncrement(), tied.size());
    return tied.get(index);
  }

  public void begin(E candidate) {
    outstanding.computeIfAbsent(candidate, key -> new AtomicInteger()).incrementAndGet();
  }

  public void end(E candidate) {
    AtomicInteger counter = outstanding.get(candidate);
    if (counter != null) {
      counter.updateAndGet(n -> Math.max(0, n - 1));
    }
  }
}
