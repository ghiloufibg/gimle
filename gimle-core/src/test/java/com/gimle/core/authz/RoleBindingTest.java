package com.gimle.core.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RoleBindingTest {

  @Test
  void user_subject_and_group_subject_produce_the_expected_prefix() {
    assertEquals("user:alice", RoleBinding.userSubject("alice"));
    assertEquals("group:gimle:operators", RoleBinding.groupSubject("gimle:operators"));
  }

  @Test
  void rejects_a_subject_with_neither_prefix() {
    assertThrows(
        IllegalArgumentException.class, () -> new RoleBinding("b1", "alice", "cluster-admin"));
  }

  @Test
  void accepts_a_well_formed_user_or_group_subject() {
    new RoleBinding("b1", RoleBinding.userSubject("alice"), "cluster-admin");
    new RoleBinding("b2", RoleBinding.groupSubject("gimle:operators"), "cluster-admin");
  }

  /**
   * subject and roleName are matched for exact string equality elsewhere (Authorizer's own
   * matchingSubjects set, and a stored Role's own name) -- incidental whitespace from an API body
   * or copy-pasted CLI argument must not survive into the stored value, or the binding would
   * silently grant nothing with no error to say why.
   */
  @Test
  void trims_incidental_whitespace_from_subject_and_role_name() {
    RoleBinding binding = new RoleBinding("b1", "  user:alice  ", "  cluster-admin  ");

    assertEquals("user:alice", binding.subject());
    assertEquals("cluster-admin", binding.roleName());
  }
}
