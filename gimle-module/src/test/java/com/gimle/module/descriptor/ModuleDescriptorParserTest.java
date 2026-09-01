package com.gimle.module.descriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ReclaimPolicy;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code health:} block's own timing fields; other fields are exercised end to end via
 * {@code TestModuleBuilder}-backed integration tests elsewhere in this module.
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
  void health_with_no_timing_fields_leaves_every_one_of_them_empty() {
    ModuleDescriptor descriptor =
        ModuleDescriptorParser.parse(
            yaml(
                BASE
                    + """
                    health:
                      liveness: com.gimle.example.SomeProbe
                    """));

    assertTrue(descriptor.healthProbes().interval().isEmpty());
    assertTrue(descriptor.healthProbes().timeout().isEmpty());
    assertTrue(descriptor.healthProbes().livenessFailureThreshold().isEmpty());
  }

  @Test
  void parses_per_module_interval_timeout_and_failure_threshold() {
    ModuleDescriptor descriptor =
        ModuleDescriptorParser.parse(
            yaml(
                BASE
                    + """
                    health:
                      readiness: com.gimle.example.SlowReadinessProbe
                      intervalSeconds: 10
                      timeoutSeconds: 30
                      failureThreshold: 6
                    """));

    assertEquals(Duration.ofSeconds(10), descriptor.healthProbes().interval().orElseThrow());
    assertEquals(Duration.ofSeconds(30), descriptor.healthProbes().timeout().orElseThrow());
    assertEquals(6, descriptor.healthProbes().livenessFailureThreshold().orElseThrow());
  }

  @Test
  void zero_interval_seconds_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ModuleDescriptorParser.parse(
                yaml(
                    BASE
                        + """
                        health:
                          liveness: com.gimle.example.SomeProbe
                          intervalSeconds: 0
                        """)));
  }

  @Test
  void negative_interval_seconds_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ModuleDescriptorParser.parse(
                yaml(
                    BASE
                        + """
                        health:
                          liveness: com.gimle.example.SomeProbe
                          intervalSeconds: -5
                        """)));
  }

  @Test
  void zero_timeout_seconds_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ModuleDescriptorParser.parse(
                yaml(
                    BASE
                        + """
                        health:
                          liveness: com.gimle.example.SomeProbe
                          timeoutSeconds: 0
                        """)));
  }

  @Test
  void non_numeric_timeout_seconds_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ModuleDescriptorParser.parse(
                yaml(
                    BASE
                        + """
                        health:
                          liveness: com.gimle.example.SomeProbe
                          timeoutSeconds: eventually
                        """)));
  }

  @Test
  void failure_threshold_below_one_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ModuleDescriptorParser.parse(
                yaml(
                    BASE
                        + """
                        health:
                          liveness: com.gimle.example.SomeProbe
                          failureThreshold: 0
                        """)));
  }

  @Test
  void non_numeric_failure_threshold_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ModuleDescriptorParser.parse(
                yaml(
                    BASE
                        + """
                        health:
                          liveness: com.gimle.example.SomeProbe
                          failureThreshold: many
                        """)));
  }

  @Test
  void no_volume_leaves_it_empty() {
    ModuleDescriptor descriptor = ModuleDescriptorParser.parse(yaml(BASE));

    assertTrue(descriptor.volumes().isEmpty());
  }

  @Test
  void parses_volume_size_with_reclaim_policy_defaulting_to_retain() {
    ModuleDescriptor descriptor =
        ModuleDescriptorParser.parse(
            yaml(
                BASE
                    + """
                    volume:
                      sizeBytes: 10737418240
                    """));

    assertEquals(10737418240L, descriptor.volumes().get("data").sizeBytes());
    assertEquals(ReclaimPolicy.RETAIN, descriptor.volumes().get("data").reclaimPolicy());
  }

  @Test
  void parses_multiple_named_volumes() {
    ModuleDescriptor descriptor =
        ModuleDescriptorParser.parse(
            yaml(
                BASE
                    + """
                    volumes:
                      data:
                        sizeBytes: 10737418240
                      wal:
                        sizeBytes: 1073741824
                        reclaimPolicy: Delete
                    """));

    assertEquals(2, descriptor.volumes().size());
    assertEquals(10737418240L, descriptor.volumes().get("data").sizeBytes());
    assertEquals(ReclaimPolicy.RETAIN, descriptor.volumes().get("data").reclaimPolicy());
    assertEquals(ReclaimPolicy.DELETE, descriptor.volumes().get("wal").reclaimPolicy());
  }

  @Test
  void declaring_both_volume_and_volumes_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ModuleDescriptorParser.parse(
                yaml(
                    BASE
                        + """
                        volume:
                          sizeBytes: 1024
                        volumes:
                          data:
                            sizeBytes: 1024
                        """)));
  }

  @Test
  void parses_explicit_delete_reclaim_policy() {
    ModuleDescriptor descriptor =
        ModuleDescriptorParser.parse(
            yaml(
                BASE
                    + """
                    volume:
                      sizeBytes: 10737418240
                      reclaimPolicy: Delete
                    """));

    assertEquals(ReclaimPolicy.DELETE, descriptor.volumes().get("data").reclaimPolicy());
  }

  @Test
  void volume_with_unknown_reclaim_policy_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ModuleDescriptorParser.parse(
                yaml(
                    BASE
                        + """
                        volume:
                          sizeBytes: 10737418240
                          reclaimPolicy: Recycle
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
                        """)));
  }
}
