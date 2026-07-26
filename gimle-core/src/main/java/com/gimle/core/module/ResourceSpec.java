package com.gimle.core.module;

/** A memory/cpu quantity pair, e.g. {@code memory="128Mi", cpu="250m"}. */
public record ResourceSpec(String memory, String cpu) {

  public ResourceSpec {
    if (memory == null || memory.isBlank()) {
      throw new IllegalArgumentException("memory must not be blank");
    }
    if (cpu == null || cpu.isBlank()) {
      throw new IllegalArgumentException("cpu must not be blank");
    }
    // Fail fast: an unparseable quantity is rejected at construction, not at first comparison.
    ResourceQuantity.parseMemory(memory);
    ResourceQuantity.parseCpu(cpu);
  }

  public long memoryBytes() {
    return ResourceQuantity.parseMemory(memory);
  }

  public long cpuMillicores() {
    return ResourceQuantity.parseCpu(cpu);
  }
}
