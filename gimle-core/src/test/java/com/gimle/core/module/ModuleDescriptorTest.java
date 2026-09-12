package com.gimle.core.module;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
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
                Optional.empty(),
                Optional.empty(),
                Map.of()));
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
                Optional.empty(),
                Optional.empty(),
                Map.of()));
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
                Optional.empty(),
                Optional.empty(),
                Map.of()));
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
                Optional.empty(),
                Optional.empty(),
                Map.of()));
  }

  @Test
  void accepts_an_ordinary_volume_name() {
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
                Optional.empty(),
                Optional.empty(),
                Map.of("data", new VolumeRequest(1024))));
  }

  /**
   * A volume name becomes a raw path segment {@code LocalDiskVolumeManager} joins straight onto
   * the instance's own data directory -- a blank check alone let a traversal sequence through
   * unchanged, deleting or writing arbitrary files outside the instance's own sandbox once that
   * volume was later released or destroyed.
   */
  @Test
  void rejects_a_volume_name_containing_a_path_traversal_sequence() {
    assertThrows(
        IllegalArgumentException.class,
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
                Optional.empty(),
                Optional.empty(),
                Map.of("../../../../etc/evil", new VolumeRequest(1024))));
  }

  @Test
  void rejects_an_absolute_volume_name() {
    assertThrows(
        IllegalArgumentException.class,
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
                Optional.empty(),
                Optional.empty(),
                Map.of("/etc/evil", new VolumeRequest(1024))));
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
            Optional.empty(),
            Optional.empty(),
            Map.of());
    assertEquals(new ModuleId("com.gimle.example.orders", Version.parse("1.4.2")), descriptor.id());
  }
}
