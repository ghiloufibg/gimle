package com.gimle.module.descriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ModuleDescriptor;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Covers {@code health.initialDelaySeconds}; other fields are exercised end to end via {@code
 * TestModuleBuilder}-backed integration tests elsewhere in this module.
 */
class ModuleDescriptorParserTest {

  private static InputStream yaml(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  private static final String BASE =
      """
      name: com.gimle.example.orders
      version: 1.0.0
      isolation:
        tier: TIER_1
      resources:
        request:
          memory: 16Mi
          cpu: 10m
        limit:
          memory: 32Mi
          cpu: 50m
      """;

  @Test
  void health_with_no_initial_delay_leaves_it_empty() {
    ModuleDescriptor descriptor =
        ModuleDescriptorParser.parse(
            yaml(
                BASE
                    + """
                    health:
                      liveness: com.gimle.example.SomeProbe
                    """));

    assertTrue(descriptor.healthProbes().initialDelay().isEmpty());
  }

  @Test
  void parses_initial_delay_seconds_into_a_duration() {
    ModuleDescriptor descriptor =
        ModuleDescriptorParser.parse(
            yaml(
                BASE
                    + """
                    health:
                      liveness: com.gimle.example.SomeProbe
                      initialDelaySeconds: 30
                    """));

    assertEquals(Duration.ofSeconds(30), descriptor.healthProbes().initialDelay().orElseThrow());
  }

  @Test
  void negative_initial_delay_seconds_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ModuleDescriptorParser.parse(
                yaml(
                    BASE
                        + """
                        health:
                          liveness: com.gimle.example.SomeProbe
                          initialDelaySeconds: -1
                        """)));
  }

  @Test
  void non_numeric_initial_delay_seconds_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ModuleDescriptorParser.parse(
                yaml(
                    BASE
                        + """
                        health:
                          liveness: com.gimle.example.SomeProbe
                          initialDelaySeconds: soon
                        """)));
  }

  @Test
  void no_volume_leaves_it_empty() {
    ModuleDescriptor descriptor = ModuleDescriptorParser.parse(yaml(BASE));

    assertTrue(descriptor.volume().isEmpty());
  }

  @Test
  void parses_volume_size_and_mount_path() {
    ModuleDescriptor descriptor =
        ModuleDescriptorParser.parse(
            yaml(
                BASE
                    + """
                    volume:
                      sizeBytes: 10737418240
                      mountPath: /data
                    """));

    assertEquals(10737418240L, descriptor.volume().orElseThrow().sizeBytes());
    assertEquals("/data", descriptor.volume().orElseThrow().mountPath());
  }

  @Test
  void volume_with_missing_mount_path_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ModuleDescriptorParser.parse(
                yaml(
                    BASE
                        + """
                        volume:
                          sizeBytes: 10737418240
                        """)));
  }

  @Test
  void volume_with_non_positive_size_bytes_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ModuleDescriptorParser.parse(
                yaml(
                    BASE
                        + """
                        volume:
                          sizeBytes: 0
                          mountPath: /data
                        """)));
  }
}
