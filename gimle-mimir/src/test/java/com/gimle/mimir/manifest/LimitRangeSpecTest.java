package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.module.ResourceSpec;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LimitRangeSpecTest {

  @Test
  void rejects_a_blank_tenant_id() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LimitRangeSpec(
                " ", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
  }

  @Test
  void rejects_a_null_bound_field() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LimitRangeSpec(
                "tenant-a", null, Optional.empty(), Optional.empty(), Optional.empty()));
  }

  @Test
  void rejects_min_request_above_max_request() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LimitRangeSpec(
                "tenant-a",
                Optional.of(new ResourceSpec("512Mi", "100m")),
                Optional.of(new ResourceSpec("256Mi", "100m")),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  void rejects_min_limit_above_max_limit() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LimitRangeSpec(
                "tenant-a",
                Optional.empty(),
                Optional.empty(),
                Optional.of(new ResourceSpec("128Mi", "500m")),
                Optional.of(new ResourceSpec("128Mi", "250m"))));
  }

  @Test
  void allows_only_some_bounds_present() {
    LimitRangeSpec spec =
        assertDoesNotThrow(
            () ->
                new LimitRangeSpec(
                    "tenant-a",
                    Optional.of(new ResourceSpec("128Mi", "100m")),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));

    assertEquals("128Mi", spec.minRequest().orElseThrow().memory());
  }

  @Test
  void does_not_check_min_request_against_max_limit() {
    // No meaningful relationship between the request pair and the limit pair -- a minRequest
    // above maxLimit is not itself a contradiction (e.g. a tenant tightening only its limit
    // ceiling independently of its request floor).
    assertDoesNotThrow(
        () ->
            new LimitRangeSpec(
                "tenant-a",
                Optional.of(new ResourceSpec("512Mi", "100m")),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new ResourceSpec("256Mi", "100m"))));
  }
}
