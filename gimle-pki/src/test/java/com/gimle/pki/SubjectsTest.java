package com.gimle.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.bouncycastle.asn1.x500.X500Name;
import org.junit.jupiter.api.Test;

class SubjectsTest {

  @Test
  void replaces_an_existing_organization_and_keeps_the_common_name() {
    X500Name result =
        Subjects.withOrganization(new X500Name("O=gimle:operators,CN=alice"), "gimle:nodes");
    assertEquals("O=gimle:nodes,CN=alice", result.toString());
  }

  @Test
  void adds_an_organization_to_a_subject_that_had_none() {
    X500Name result = Subjects.withOrganization(new X500Name("CN=node-1"), "gimle:nodes");
    assertEquals("O=gimle:nodes,CN=node-1", result.toString());
  }

  @Test
  void rejects_a_subject_with_no_common_name() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Subjects.withOrganization(new X500Name("O=some-org"), "gimle:nodes"));
  }
}
