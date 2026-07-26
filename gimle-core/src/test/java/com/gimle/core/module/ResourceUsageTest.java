package com.gimle.core.module;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ResourceUsageTest {

  @Test
  void accepts_non_negative_usage() {
    assertDoesNotThrow(() -> new ResourceUsage(0, 0));
    assertDoesNotThrow(() -> new ResourceUsage(1024, 100));
  }

  @Test
  void rejects_negative_memory() {
    assertThrows(IllegalArgumentException.class, () -> new ResourceUsage(-1, 0));
  }

  @Test
  void rejects_negative_cpu() {
    assertThrows(IllegalArgumentException.class, () -> new ResourceUsage(0, -1));
  }
}
