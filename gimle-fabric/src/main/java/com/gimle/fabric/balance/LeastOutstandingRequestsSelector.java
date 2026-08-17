package com.gimle.fabric.balance;

import java.util.ArrayList;
import java.util.Iterator;
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

  /**
   * Cap on distinct tracked endpoints, so long-lived-process endpoint churn (worker respawns
   * minting new ports) can't grow {@link #outstanding} without bound. Not a real LRU -- just an
   * arbitrary-entry eviction once the cap is exceeded, cheap and good enough since a mistakenly
   * evicted still-live endpoint simply gets a fresh zeroed counter on its next lookup.
   */
  private static final int MAX_TRACKED_ENDPOINTS = 2000;

  private final Map<E, AtomicInteger> outstanding = new ConcurrentHashMap<>();
  private final AtomicInteger roundRobinCursor = new AtomicInteger();

  public E select(List<E> candidates) {
    if (candidates.isEmpty()) {
      throw new NoSuchElementException("no candidates to select from");
    }
    int minOutstanding = Integer.MAX_VALUE;
    List<E> tied = new ArrayList<>();
    for (E candidate : candidates) {
      int count = counterFor(candidate).get();
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
    counterFor(candidate).incrementAndGet();
  }

  private AtomicInteger counterFor(E candidate) {
    AtomicInteger counter = outstanding.computeIfAbsent(candidate, key -> new AtomicInteger());
    if (outstanding.size() > MAX_TRACKED_ENDPOINTS) {
      Iterator<E> it = outstanding.keySet().iterator();
      if (it.hasNext()) {
        it.next();
        it.remove();
      }
    }
    return counter;
  }

  public void end(E candidate) {
    AtomicInteger counter = outstanding.get(candidate);
    if (counter != null) {
      counter.updateAndGet(n -> Math.max(0, n - 1));
    }
  }

  /**
   * Current outstanding count for {@code candidate}, {@code 0} if it has none in flight (or has
   * never been seen). Read-only -- doesn't call {@link #begin}, so it never creates an entry.
   * Exists for capacity-aware locality decisions upstream of selection itself (P3: a caller
   * comparing load across two candidate tiers before ever calling {@link #select}), not for
   * anything inside this class.
   */
  public int outstandingCount(E candidate) {
    AtomicInteger counter = outstanding.get(candidate);
    return counter == null ? 0 : counter.get();
  }
}
