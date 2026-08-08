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
}
