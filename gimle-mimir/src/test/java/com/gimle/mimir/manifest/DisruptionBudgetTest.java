package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@link DisruptionBudget} validation and defaulting -- manifest-level parsing/rejection (the
 * {@code maxSurge}-not-implemented-yet and DaemonSet {@code maxSurge}-meaningless rejections) is
 * covered by {@code DeploymentManifestParserTest}/{@code DaemonSetManifestParserTest} instead.
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
  void max_unavailable_must_be_at_least_1() {
    assertThrows(IllegalArgumentException.class, () -> new DisruptionBudget(0, 0));
    assertThrows(IllegalArgumentException.class, () -> new DisruptionBudget(-1, 0));
  }

  @Test
  void max_surge_must_not_be_negative() {
    assertThrows(IllegalArgumentException.class, () -> new DisruptionBudget(1, -1));
  }

  @Test
  void a_nonzero_max_surge_is_accepted_by_the_record_itself() {
    // The record itself doesn't reject maxSurge > 0 -- only DeploymentManifestParser/
    // DaemonSetManifestParser do, at the manifest layer, since "not implemented yet" and
    // "meaningless on this workload kind" are business rules, not this type's own invariant.
    DisruptionBudget budget = new DisruptionBudget(2, 1);

    assertEquals(2, budget.maxUnavailable());
    assertEquals(1, budget.maxSurge());
  }
}
