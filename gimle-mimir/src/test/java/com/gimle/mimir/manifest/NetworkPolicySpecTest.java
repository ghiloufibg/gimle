package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NetworkPolicySpecTest {

  @Test
  void rejects_a_blank_name() {
    assertThrows(
        IllegalArgumentException.class, () -> new NetworkPolicySpec(" ", "tenant-a", Set.of()));
  }

  @Test
  void rejects_a_blank_tenant_id() {
    assertThrows(
        IllegalArgumentException.class, () -> new NetworkPolicySpec("policy", " ", Set.of()));
  }

  @Test
  void rejects_a_null_allowed_caller_tenant_ids() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new NetworkPolicySpec("policy", "tenant-a", (Set<String>) null));
  }

  @Test
  void the_three_arg_constructor_scopes_to_the_whole_tenant() {
    NetworkPolicySpec policy = new NetworkPolicySpec("policy", "tenant-a", Set.of());

    assertEquals(Optional.empty(), policy.deploymentNames());
  }

  @Test
  void same_tenant_callers_are_always_permitted() {
    NetworkPolicySpec policy = new NetworkPolicySpec("policy", "tenant-a", Set.of());

    assertTrue(policy.permitsCallerTenant(Optional.of("tenant-a")));
  }

  @Test
  void an_untenanted_caller_is_never_permitted() {
    NetworkPolicySpec policy = new NetworkPolicySpec("policy", "tenant-a", Set.of("tenant-b"));

    assertFalse(policy.permitsCallerTenant(Optional.empty()));
  }

  @Test
  void a_caller_not_on_the_allow_list_is_denied() {
    NetworkPolicySpec policy = new NetworkPolicySpec("policy", "tenant-a", Set.of("tenant-b"));

    assertFalse(policy.permitsCallerTenant(Optional.of("tenant-c")));
  }

  @Test
  void a_caller_on_the_allow_list_is_permitted() {
    NetworkPolicySpec policy = new NetworkPolicySpec("policy", "tenant-a", Set.of("tenant-b"));

    assertTrue(policy.permitsCallerTenant(Optional.of("tenant-b")));
  }

  @Test
  void an_empty_allow_list_denies_every_cross_tenant_caller() {
    NetworkPolicySpec policy = new NetworkPolicySpec("policy", "tenant-a", Set.of());

    assertFalse(policy.permitsCallerTenant(Optional.of("tenant-b")));
  }
}
