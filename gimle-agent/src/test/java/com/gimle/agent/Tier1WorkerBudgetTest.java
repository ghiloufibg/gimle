package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The admission arithmetic behind Tier 1 density: a shared worker is sized by the node, not by
 * whichever instance reached it first, and instances are admitted into it only while their declared
 * limits still fit.
 */
class Tier1WorkerBudgetTest {

  private static ModuleDescriptor module(String memory, String cpu) {
    return new ModuleDescriptor(
        "m-" + memory + "-" + cpu,
        Version.parse("1.0.0"),
        List.of(),
        List.of(),
        IsolationTier.TIER_1,
        new ResourceSpec("8Mi", "100m"),
        new ResourceSpec(memory, cpu),
        HealthProbes.NONE,
        Optional.empty(),
        Optional.empty(),
        Map.of());
  }

  @Test
  void an_unset_property_falls_back_to_the_declared_default() {
    Tier1WorkerBudget budget = Tier1WorkerBudget.parse(null, "  ", null);

    assertEquals(
        new ResourceSpec(Tier1WorkerBudget.DEFAULT_HEAP, "1").memoryBytes(), budget.heapBytes());
    assertEquals(
        new ResourceSpec("1Mi", Tier1WorkerBudget.DEFAULT_CPU).cpuMillicores(),
        budget.cpuMillicores());
  }

  @Test
  void a_malformed_quantity_names_the_property_it_came_from() {
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class, () -> Tier1WorkerBudget.parse("plenty", null, null));

    assertTrue(failure.getMessage().contains(Tier1WorkerBudget.HEAP_PROPERTY));
  }

  @Test
  void a_reserve_as_large_as_the_heap_is_rejected_rather_than_admitting_nothing() {
    // Silently accepting it would leave the agent unable to pack any instance at all, with nothing
    // anywhere saying why -- exactly the failure shape this budget exists to remove.
    assertThrows(
        IllegalArgumentException.class, () -> Tier1WorkerBudget.parse("128Mi", "1000m", "128Mi"));
  }

  @Test
  void a_shared_worker_takes_the_declared_budget_rather_than_its_first_instances_limit() {
    Tier1WorkerBudget budget = Tier1WorkerBudget.parse("1Gi", "2000m", "128Mi");

    ResourceSpec size = budget.sizeFor(module("12Mi", "100m"));

    assertEquals(new ResourceSpec("1Gi", "2000m").memoryBytes(), size.memoryBytes());
    assertEquals(2000L, size.cpuMillicores());
  }

  @Test
  void a_module_larger_than_the_budget_still_gets_the_heap_its_manifest_declares() {
    // The inverse of the arbitrary-sizing bug: a fixed budget must not become a fixed ceiling that
    // strangles a module under a number it never asked for.
    Tier1WorkerBudget budget = Tier1WorkerBudget.parse("128Mi", "1000m", "32Mi");

    ResourceSpec size = budget.sizeFor(module("512Mi", "4000m"));

    assertEquals(new ResourceSpec("544Mi", "1000m").memoryBytes(), size.memoryBytes());
    assertEquals(4000L, size.cpuMillicores());
  }

  @Test
  void admission_sums_declared_limits_against_the_heap_left_after_the_overhead_reserve() {
    Tier1WorkerBudget budget = Tier1WorkerBudget.parse("256Mi", "4000m", "32Mi");
    ResourceSpec worker = new ResourceSpec("256Mi", "4000m");

    assertTrue(budget.admits(worker, List.of(module("128Mi", "500m")), module("96Mi", "500m")));
    assertFalse(budget.admits(worker, List.of(module("128Mi", "500m")), module("97Mi", "500m")));
  }

  @Test
  void an_empty_worker_still_cannot_admit_a_claim_larger_than_its_usable_heap() {
    Tier1WorkerBudget budget = Tier1WorkerBudget.parse("256Mi", "4000m", "32Mi");

    assertFalse(
        budget.admits(new ResourceSpec("256Mi", "4000m"), List.of(), module("250Mi", "500m")));
  }

  @Test
  void cpu_is_deliberately_not_summed() {
    // Heap runs out permanently and takes the JVM with it; CPU is time-shared and merely gets
    // slower under contention. Summing it would refuse to pack two modules that each declare a
    // whole worker's worth of CPU -- an ordinary and harmless thing to declare.
    Tier1WorkerBudget budget = Tier1WorkerBudget.parse("1Gi", "2000m", "128Mi");
    ResourceSpec worker = new ResourceSpec("1Gi", "2000m");

    assertTrue(budget.admits(worker, List.of(module("8Mi", "2000m")), module("8Mi", "2000m")));
  }
}
