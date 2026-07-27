package com.gimle.fabric.balance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Least-outstanding-requests selection among a candidate set, with ties broken round-robin. Used by
 * the proxy's {@code InvocationHandler} dispatch layer, which calls {@link #begin}/{@link #end}
 * around each cross-hop invocation -- outstanding counts can only be tracked where a dispatch layer
 * exists to instrument them, which same-worker calls deliberately don't have.
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
