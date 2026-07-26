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
    ResourceQuantity.parse_memory(memory);
    ResourceQuantity.parse_cpu(cpu);
  }

  public long memory_bytes() {
    return ResourceQuantity.parse_memory(memory);
  }

  public long cpu_millicores() {
    return ResourceQuantity.parse_cpu(cpu);
  }
}
