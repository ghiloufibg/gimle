package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class AlertRuleSpecTest {

  @Test
  void rejects_a_blank_name() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AlertRuleSpec(
                " ",
                Optional.empty(),
                "checkout-service",
                AlertRuleSpec.Metric.ERROR_RATE_PER_SECOND,
                AlertRuleSpec.Comparator.GREATER_THAN,
                5.0,
                "https://hooks.example.com/alerts"));
  }

  @Test
  void rejects_a_blank_deployment_name() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AlertRuleSpec(
                "high-errors",
                Optional.empty(),
                " ",
                AlertRuleSpec.Metric.ERROR_RATE_PER_SECOND,
                AlertRuleSpec.Comparator.GREATER_THAN,
                5.0,
                "https://hooks.example.com/alerts"));
  }

  @Test
  void rejects_a_null_tenant_id() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AlertRuleSpec(
                "high-errors",
                null,
                "checkout-service",
                AlertRuleSpec.Metric.ERROR_RATE_PER_SECOND,
                AlertRuleSpec.Comparator.GREATER_THAN,
                5.0,
                "https://hooks.example.com/alerts"));
  }

  @Test
  void rejects_a_null_metric() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AlertRuleSpec(
                "high-errors",
                Optional.empty(),
                "checkout-service",
                null,
                AlertRuleSpec.Comparator.GREATER_THAN,
                5.0,
                "https://hooks.example.com/alerts"));
  }

  @Test
  void rejects_a_null_comparator() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AlertRuleSpec(
                "high-errors",
                Optional.empty(),
                "checkout-service",
                AlertRuleSpec.Metric.ERROR_RATE_PER_SECOND,
                null,
                5.0,
                "https://hooks.example.com/alerts"));
  }

  @Test
  void rejects_a_blank_webhook_url() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AlertRuleSpec(
                "high-errors",
                Optional.empty(),
                "checkout-service",
                AlertRuleSpec.Metric.ERROR_RATE_PER_SECOND,
                AlertRuleSpec.Comparator.GREATER_THAN,
                5.0,
                " "));
  }

  @Test
  void the_seven_argument_constructor_defaults_enabled_to_true() {
    AlertRuleSpec spec =
        new AlertRuleSpec(
            "high-errors",
            Optional.empty(),
            "checkout-service",
            AlertRuleSpec.Metric.ERROR_RATE_PER_SECOND,
            AlertRuleSpec.Comparator.GREATER_THAN,
            5.0,
            "https://hooks.example.com/alerts");
    assertTrue(spec.enabled());
  }

  @Test
  void greater_than_crosses_only_above_threshold() {
    AlertRuleSpec spec = rule(AlertRuleSpec.Comparator.GREATER_THAN, 5.0);
    assertTrue(spec.crosses(5.1));
    assertFalse(spec.crosses(5.0));
    assertFalse(spec.crosses(4.9));
  }

  @Test
  void less_than_crosses_only_below_threshold() {
    AlertRuleSpec spec = rule(AlertRuleSpec.Comparator.LESS_THAN, 5.0);
    assertTrue(spec.crosses(4.9));
    assertFalse(spec.crosses(5.0));
    assertFalse(spec.crosses(5.1));
  }

  @Test
  void tenant_id_is_preserved_when_present() {
    AlertRuleSpec spec =
        new AlertRuleSpec(
            "high-errors",
            Optional.of("acme"),
            "checkout-service",
            AlertRuleSpec.Metric.ERROR_RATE_PER_SECOND,
            AlertRuleSpec.Comparator.GREATER_THAN,
            5.0,
            "https://hooks.example.com/alerts");
    assertEquals(Optional.of("acme"), spec.tenantId());
  }

  private static AlertRuleSpec rule(AlertRuleSpec.Comparator comparator, double threshold) {
    return new AlertRuleSpec(
        "high-errors",
        Optional.empty(),
        "checkout-service",
        AlertRuleSpec.Metric.ERROR_RATE_PER_SECOND,
        comparator,
        threshold,
        "https://hooks.example.com/alerts");
  }
}
