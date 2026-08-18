package com.gimle.core.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NetworkPolicyRuleTest {

  @Test
  void the_back_compat_constructor_defaults_deployment_names_to_empty() {
    NetworkPolicyRule rule = new NetworkPolicyRule("deny-by-default", "acme", Set.of());

    assertEquals(Optional.empty(), rule.deploymentNames());
  }

  @Test
  void rejects_a_null_deployment_names() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new NetworkPolicyRule("deny-by-default", "acme", null, Set.of()));
  }

  @Test
  void a_tenant_wide_rule_applies_to_every_deployment_including_an_unknown_one() {
    NetworkPolicyRule rule = new NetworkPolicyRule("deny-by-default", "acme", Set.of());

    assertTrue(rule.appliesToDeployment(Optional.of("orders")));
    assertTrue(rule.appliesToDeployment(Optional.empty()));
  }

  @Test
  void a_deployment_scoped_rule_applies_only_to_its_named_deployments() {
    NetworkPolicyRule rule =
        new NetworkPolicyRule("deny-by-default", "acme", Optional.of(Set.of("orders")), Set.of());

    assertTrue(rule.appliesToDeployment(Optional.of("orders")));
    assertFalse(rule.appliesToDeployment(Optional.of("payments")));
  }

  @Test
  void a_deployment_scoped_rule_never_applies_to_an_unknown_deployment() {
    NetworkPolicyRule rule =
        new NetworkPolicyRule("deny-by-default", "acme", Optional.of(Set.of("orders")), Set.of());

    assertFalse(rule.appliesToDeployment(Optional.empty()));
  }

  @Test
  void permits_caller_tenant_semantics_are_unaffected_by_deployment_scoping() {
    NetworkPolicyRule rule =
        new NetworkPolicyRule(
            "allow-partner", "acme", Optional.of(Set.of("orders")), Set.of("partner"));

    assertTrue(rule.permitsCallerTenant(Optional.of("acme")));
    assertTrue(rule.permitsCallerTenant(Optional.of("partner")));
    assertFalse(rule.permitsCallerTenant(Optional.of("stranger")));
    assertFalse(rule.permitsCallerTenant(Optional.empty()));
  }
}
