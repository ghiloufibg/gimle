package com.gimle.agent;

import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ResourceSpec;
import java.util.Collection;

/**
 * How large a shared Tier 1 worker JVM is, and how much of that JVM the instances packed into it
 * may claim between them.
 *
 * <p>A Tier 1 worker hosts several instances behind one heap, so at most one of them could ever be
 * the one to set its {@code -Xmx} -- and letting whichever instance happened to arrive first set it
 * makes every other instance's declared {@code resources.limit} both unenforced and unpredictable:
 * too small a worker strangles a module under a ceiling it never asked for, too large a one lets a
 * module allocate far past the bound its own manifest states. This type replaces that with a fixed,
 * declared size: a shared worker is sized from {@link #heapBytes}/{@link #cpuMillicores}, and an
 * instance is admitted into it only while the declared limits of everything already resident, plus
 * its own, still fit inside that size.
 *
 * <p>Limits are summed, never requests. Requests may be oversubscribed where each workload gets its
 * own enforced ceiling, because the ceilings are what the machine actually has to honour. Here
 * there is no per-instance ceiling to fall back on -- one heap, no partition -- so admitting on
 * requests would mean every co-tenant simultaneously reaching the bound its manifest promises is
 * exactly the case that OOMs the worker.
 *
 * <p>{@link #overheadReserveBytes} is held back from every worker's heap before any instance is
 * admitted. The worker JVM pays for itself before it hosts anything: the module runtime, the fabric
 * server and its connection buffers, the metrics registry, the leak detector's recording stream,
 * and a scheduler per hosted instance. Admitting instances up to the full heap would leave that
 * overhead nothing to run in, and the worker would die on its own footprint with every co-tenant
 * still inside its declared bound.
 *
 * <p>Only memory is summed. Heap is a finite quantity that runs out permanently and takes the whole
 * JVM with it; CPU is time-shared and merely gets slower under contention, so summing declared CPU
 * limits would collapse density -- a module declaring a whole worker's worth of CPU is ordinary and
 * harmless -- to prevent a failure that does not exist. A worker is still never given fewer
 * processors than its largest resident asked for; that is {@link #sizeFor}'s job, not admission's.
 *
 * <p>What this does not do is enforce anything per instance. One JVM has one heap, so a module that
 * exceeds its declared limit still allocates against the whole worker and can still OOM its
 * co-tenants. This makes a Tier 1 limit a real admission input with a predictable worker behind it
 * -- it does not make it a per-instance ceiling, which no arrangement of JVM flags can provide.
 * That remains the reason Tier 2 exists.
 */
record Tier1WorkerBudget(long heapBytes, long cpuMillicores, long overheadReserveBytes) {

  /**
   * Big enough that several ordinary modules pack together without immediately overflowing, small
   * enough that a node running a handful of shared workers is not committing its whole address
   * space to heap ceilings. A starting point, not a measured optimum -- the right value is a
   * property of the node and of how large the modules it hosts declare themselves to be, which is
   * why it is a knob.
   */
  static final String DEFAULT_HEAP = "1Gi";

  static final String DEFAULT_CPU = "2000m";

  static final String DEFAULT_OVERHEAD_RESERVE = "128Mi";

  static final String HEAP_PROPERTY = "gimle.agent.tier1WorkerHeap";
  static final String CPU_PROPERTY = "gimle.agent.tier1WorkerCpu";
  static final String OVERHEAD_RESERVE_PROPERTY = "gimle.agent.tier1WorkerOverheadReserve";

  Tier1WorkerBudget {
    if (heapBytes <= 0) {
      throw new IllegalArgumentException(HEAP_PROPERTY + " must be positive, got: " + heapBytes);
    }
    if (cpuMillicores <= 0) {
      throw new IllegalArgumentException(CPU_PROPERTY + " must be positive, got: " + cpuMillicores);
    }
    if (overheadReserveBytes < 0) {
      throw new IllegalArgumentException(
          OVERHEAD_RESERVE_PROPERTY + " must not be negative, got: " + overheadReserveBytes);
    }
    if (overheadReserveBytes >= heapBytes) {
      throw new IllegalArgumentException(
          OVERHEAD_RESERVE_PROPERTY
              + " ("
              + ResourceSpec.formatMemory(overheadReserveBytes)
              + ") must be smaller than "
              + HEAP_PROPERTY
              + " ("
              + ResourceSpec.formatMemory(heapBytes)
              + "), or no instance could ever be admitted to a shared worker");
    }
  }

  /**
   * Reads the budget an agent runs with, failing at startup on anything unparseable rather than
   * quietly reverting to the defaults -- an operator who set one of these meant to change how this
   * node packs, and a value that silently does nothing is worse than a refusal that names it.
   */
  static Tier1WorkerBudget fromSystemProperties() {
    return parse(
        System.getProperty(HEAP_PROPERTY),
        System.getProperty(CPU_PROPERTY),
        System.getProperty(OVERHEAD_RESERVE_PROPERTY));
  }

  static Tier1WorkerBudget parse(String heap, String cpu, String overheadReserve) {
    // Read through ResourceSpec so every knob here uses the same quantity grammar a manifest does,
    // rather than a second parser that could accept or reject something differently.
    ResourceSpec worker =
        new ResourceSpec(
            memoryQuantity(HEAP_PROPERTY, heap, DEFAULT_HEAP),
            cpuQuantity(CPU_PROPERTY, cpu, DEFAULT_CPU));
    ResourceSpec reserve =
        new ResourceSpec(
            memoryQuantity(OVERHEAD_RESERVE_PROPERTY, overheadReserve, DEFAULT_OVERHEAD_RESERVE),
            DEFAULT_CPU);
    return new Tier1WorkerBudget(
        worker.memoryBytes(), worker.cpuMillicores(), reserve.memoryBytes());
  }

  private static String memoryQuantity(String property, String value, String fallback) {
    String candidate = orFallback(value, fallback);
    try {
      new ResourceSpec(candidate, DEFAULT_CPU);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          property + " must be a memory quantity such as 1Gi or 512Mi, got: " + value, e);
    }
    return candidate;
  }

  private static String cpuQuantity(String property, String value, String fallback) {
    String candidate = orFallback(value, fallback);
    try {
      new ResourceSpec(DEFAULT_HEAP, candidate);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          property + " must be a cpu quantity such as 2000m or 2, got: " + value, e);
    }
    return candidate;
  }

  private static String orFallback(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  /**
   * The size a shared worker is spawned at for {@code spawner}, its first instance. Ordinarily the
   * declared budget, but never smaller than what that instance itself asked for plus the overhead
   * reserve: a module declaring more than a whole budget's worth of heap cannot be packed with
   * anyone, and giving it a worker smaller than its own manifest states would reproduce, at a fixed
   * number instead of an arbitrary one, exactly the strangling this budget exists to remove. Such
   * an instance gets a worker sized to itself, which the admission check below then leaves
   * (correctly) with no room for a co-tenant.
   */
  ResourceSpec sizeFor(ModuleDescriptor spawner) {
    ResourceSpec limit = spawner.resourceLimit();
    return new ResourceSpec(
        ResourceSpec.formatMemory(Math.max(heapBytes, limit.memoryBytes() + overheadReserveBytes)),
        ResourceSpec.formatCpu(Math.max(cpuMillicores, limit.cpuMillicores())));
  }

  /**
   * Whether {@code candidate} still fits alongside {@code residents} in a worker spawned at {@code
   * workerSize}. {@code workerSize} is the worker's own recorded sizing rather than this budget's
   * nominal one, so a worker spawned larger than the budget (see {@link #sizeFor}) is measured
   * against what it actually got.
   */
  boolean admits(
      ResourceSpec workerSize, Collection<ModuleDescriptor> residents, ModuleDescriptor candidate) {
    long claimedMemory = candidate.resourceLimit().memoryBytes();
    for (ModuleDescriptor resident : residents) {
      claimedMemory += resident.resourceLimit().memoryBytes();
    }
    return claimedMemory <= workerSize.memoryBytes() - overheadReserveBytes;
  }
}
