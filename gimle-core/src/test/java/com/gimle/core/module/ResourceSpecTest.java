package com.gimle.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ResourceSpecTest {

  @Test
  void memory_formats_in_the_largest_binary_suffix_that_divides_it_exactly() {
    assertEquals("90Mi", ResourceSpec.formatMemory(90L * 1024 * 1024));
    assertEquals("2Gi", ResourceSpec.formatMemory(2L * 1024 * 1024 * 1024));
    assertEquals("3Ti", ResourceSpec.formatMemory(3L * 1024 * 1024 * 1024 * 1024));
    assertEquals("7Ki", ResourceSpec.formatMemory(7L * 1024));
  }

  @Test
  void memory_that_no_binary_suffix_divides_exactly_formats_as_a_bare_byte_count() {
    assertEquals("1500", ResourceSpec.formatMemory(1500));
    assertEquals("0", ResourceSpec.formatMemory(0));
  }

  /**
   * Free capacity is a computed difference, so a node assigned more than it reports total yields a
   * negative -- rendered rather than silently clamped, since a diagnostic that hides it would read
   * as "0 free" and send an operator looking for the wrong problem.
   */
  @Test
  void a_negative_quantity_keeps_its_sign() {
    assertEquals("-4Mi", ResourceSpec.formatMemory(-4L * 1024 * 1024));
    assertEquals("-250m", ResourceSpec.formatCpu(-250));
  }

  @Test
  void a_formatted_memory_quantity_parses_back_to_the_same_byte_count() {
    long bytes = 90L * 1024 * 1024;

    assertEquals(bytes, new ResourceSpec(ResourceSpec.formatMemory(bytes), "1m").memoryBytes());
  }

  @Test
  void cpu_formats_as_millicores() {
    assertEquals("250m", ResourceSpec.formatCpu(250));
    assertEquals("0m", ResourceSpec.formatCpu(0));
  }
}
