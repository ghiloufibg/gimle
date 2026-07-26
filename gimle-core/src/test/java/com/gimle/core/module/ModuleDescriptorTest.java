package com.gimle.core.module;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ModuleDescriptorTest {

  @Test
  void accepts_request_within_limit() {
    assertDoesNotThrow(
        () ->
            new ModuleDescriptor(
                "com.gimle.example.orders",
                Version.parse("1.0.0"),
                List.of(),
                List.of(),
                IsolationTier.TIER_1,
                new ResourceSpec("128Mi", "250m"),
                new ResourceSpec("256Mi", "500m"),
                HealthProbes.NONE,
                Optional.empty()));
  }

  @Test
  void rejects_memory_request_exceeding_limit() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModuleDescriptor(
                "com.gimle.example.orders",
                Version.parse("1.0.0"),
                List.of(),
                List.of(),
                IsolationTier.TIER_1,
                new ResourceSpec("512Mi", "250m"),
                new ResourceSpec("256Mi", "500m"),
                HealthProbes.NONE,
                Optional.empty()));
  }

  @Test
  void rejects_cpu_request_exceeding_limit() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModuleDescriptor(
                "com.gimle.example.orders",
                Version.parse("1.0.0"),
                List.of(),
                List.of(),
                IsolationTier.TIER_1,
                new ResourceSpec("128Mi", "750m"),
                new ResourceSpec("256Mi", "500m"),
                HealthProbes.NONE,
                Optional.empty()));
  }

  @Test
  void rejects_blank_name() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModuleDescriptor(
                " ",
                Version.parse("1.0.0"),
                List.of(),
                List.of(),
                IsolationTier.TIER_1,
                new ResourceSpec("128Mi", "250m"),
                new ResourceSpec("256Mi", "500m"),
                HealthProbes.NONE,
                Optional.empty()));
  }

  @Test
  void id_combines_name_and_version() {
    ModuleDescriptor descriptor =
        new ModuleDescriptor(
            "com.gimle.example.orders",
            Version.parse("1.4.2"),
            List.of(),
            List.of(),
            IsolationTier.TIER_1,
            new ResourceSpec("128Mi", "250m"),
            new ResourceSpec("256Mi", "500m"),
            HealthProbes.NONE,
            Optional.empty());
    assertDoesNotThrow(descriptor::id);
  }
}
