package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@link DisruptionBudget} validation and defaulting -- manifest-level parsing (Deployment now
 * accepts a nonzero {@code maxSurge}; DaemonSet's own permanent {@code maxSurge}-meaningless
 * rejection) is covered by {@code DeploymentManifestParserTest}/{@code DaemonSetManifestParserTest}
 * instead.
 */
class DisruptionBudgetTest {

  @Test
  void default_is_max_unavailable_1_with_no_surge() {
    assertEquals(1, DisruptionBudget.DEFAULT.maxUnavailable());
    assertEquals(0, DisruptionBudget.DEFAULT.maxSurge());
  }

  @Test
  void the_one_arg_constructor_defaults_max_surge_to_0() {
    DisruptionBudget budget = new DisruptionBudget(3);

    assertEquals(3, budget.maxUnavailable());
    assertEquals(0, budget.maxSurge());
  }

  @Test
  void max_unavailable_and_max_surge_must_not_both_be_0() {
    assertThrows(IllegalArgumentException.class, () -> new DisruptionBudget(0, 0));
  }

  @Test
  void max_unavailable_must_not_be_negative() {
    assertThrows(IllegalArgumentException.class, () -> new DisruptionBudget(-1, 0));
  }

  @Test
  void max_surge_must_not_be_negative() {
    assertThrows(IllegalArgumentException.class, () -> new DisruptionBudget(1, -1));
  }

  @Test
  void a_nonzero_max_surge_is_accepted_by_the_record_itself() {
    // The record itself doesn't reject maxSurge > 0 -- only DaemonSetManifestParser does, at
    // the manifest layer, since "meaningless on this workload kind" is a business rule, not this
    // type's own invariant. DeploymentManifestParser accepts a nonzero maxSurge.
    DisruptionBudget budget = new DisruptionBudget(2, 1);

    assertEquals(2, budget.maxUnavailable());
    assertEquals(1, budget.maxSurge());
  }

  @Test
  void a_pure_surge_budget_with_max_unavailable_0_is_accepted_alongside_a_nonzero_max_surge() {
    // Literal Kubernetes RollingUpdateDeployment semantics: maxUnavailable: 0 is only a stuck
    // rollout if maxSurge is also 0 -- with a nonzero maxSurge, DeploymentReconciler#handleSurge
    // alone drives the whole rollout via surge-then-promote, never removing an index before its
    // replacement lands.
    DisruptionBudget budget = new DisruptionBudget(0, 2);

    assertEquals(0, budget.maxUnavailable());
    assertEquals(2, budget.maxSurge());
  }

  @Test
  void the_one_arg_daemonset_constructor_still_rejects_0_since_it_always_pairs_with_no_surge() {
    // The single-arg constructor always delegates to (maxUnavailable, 0) -- DaemonSet has no
    // surge concept at all (DaemonSetManifestParser rejects a nonzero maxSurge outright), so 0
    // here is never rescued by a nonzero maxSurge the way the two-arg constructor allows.
    assertThrows(IllegalArgumentException.class, () -> new DisruptionBudget(0));
  }
}
