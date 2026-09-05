package com.gimle.fabric.transport;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Runs one unit of inbound-call work under whatever concurrency policy the owning worker enforces
 * for that specific module -- deliberately just the one method a caller like {@code
 * BoundedModuleScheduler#submit} already has, rather than {@code FabricServer} depending on {@code
 * gimle-worker}'s concrete scheduler type directly, which would invert the module graph ({@code
 * gimle-worker} already depends on {@code gimle-fabric}, not the other way around).
 */
@FunctionalInterface
public interface ModuleWorkExecutor {

  <T> Future<T> submit(Callable<T> task);

  /**
   * How much inbound work is queued ahead of a request submitted right now -- the target's own
   * reading of how saturated it is, which is reported back to callers so their load balancing sees
   * a replica's real backlog rather than only the requests they themselves have in flight to it.
   * Zero for an executor with no queue to speak of, which is also why this has a default: a caller
   * that hands in a bare {@code submit} lambda is saying it has no such reading to give.
   */
  default int queueDepth() {
    return 0;
  }
}
