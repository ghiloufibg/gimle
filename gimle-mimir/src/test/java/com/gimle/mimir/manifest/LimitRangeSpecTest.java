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
  void rejects_min_request_above_max_limit_memory() {
    // ModuleDescriptor's own compact constructor requires resourceRequest <= resourceLimit on
    // every manifest -- so a minRequest above maxLimit is a combination no manifest could ever
    // satisfy (its request would have to be >= minRequest > maxLimit >= its own limit).
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LimitRangeSpec(
                "tenant-a",
                Optional.of(new ResourceSpec("512Mi", "100m")),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new ResourceSpec("256Mi", "500m"))));
  }

  @Test
  void rejects_min_request_above_max_limit_cpu() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LimitRangeSpec(
                "tenant-a",
                Optional.of(new ResourceSpec("128Mi", "500m")),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new ResourceSpec("256Mi", "100m"))));
  }

  @Test
  void allows_min_request_at_max_limit_boundary() {
    // Inclusive, same as every other bound comparison here -- exactly equal is not a violation.
    assertDoesNotThrow(
        () ->
            new LimitRangeSpec(
                "tenant-a",
                Optional.of(new ResourceSpec("256Mi", "100m")),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new ResourceSpec("256Mi", "100m"))));
  }

  @Test
  void allows_min_limit_above_max_request_since_limit_may_exceed_request() {
    // The one cross-pair relationship this constructor rejects is minRequest > maxLimit; the
    // opposite pairing (minLimit vs maxRequest) has no forced relationship -- a module's own
    // limit is always allowed to exceed its request, never the reverse.
    assertDoesNotThrow(
        () ->
            new LimitRangeSpec(
                "tenant-a",
                Optional.empty(),
                Optional.of(new ResourceSpec("128Mi", "100m")),
                Optional.of(new ResourceSpec("512Mi", "500m")),
                Optional.empty()));
  }

  @Test
  void violation_is_empty_when_both_request_and_limit_satisfy_every_bound() {
    LimitRangeSpec range =
        new LimitRangeSpec(
            "tenant-a",
            Optional.of(new ResourceSpec("64Mi", "50m")),
            Optional.of(new ResourceSpec("256Mi", "500m")),
            Optional.of(new ResourceSpec("64Mi", "50m")),
            Optional.of(new ResourceSpec("512Mi", "1000m")));

    assertEquals(
        Optional.empty(),
        range.violation(new ResourceSpec("128Mi", "100m"), new ResourceSpec("256Mi", "500m")));
  }

  @Test
  void violation_reports_which_bound_a_request_or_limit_fails() {
    LimitRangeSpec range =
        new LimitRangeSpec(
            "tenant-a",
            Optional.of(new ResourceSpec("64Mi", "50m")),
            Optional.of(new ResourceSpec("256Mi", "500m")),
            Optional.empty(),
            Optional.empty());

    Optional<String> violation =
        range.violation(new ResourceSpec("512Mi", "100m"), new ResourceSpec("1Gi", "1000m"));

    assertEquals(true, violation.isPresent());
    assertEquals(true, violation.get().contains("request memory"));
    assertEquals(true, violation.get().contains("above maximum"));
  }
}
