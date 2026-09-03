package com.gimle.core.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.authz.Principal;
import java.util.Set;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.Test;

class CertificateIdentityTest {

  @Test
  void common_name_becomes_the_principal_name_and_every_organization_a_group() {
    Principal principal =
        CertificateIdentity.principalFrom(
            new X500Principal("O=gimle:workers,O=gimle:tenant:acme,CN=node-1:orders#0"));

    assertEquals("node-1:orders#0", principal.name());
    assertEquals(Set.of("gimle:workers", "gimle:tenant:acme"), principal.groups());
  }

  @Test
  void a_subject_with_no_organization_yields_no_groups() {
    Principal principal = CertificateIdentity.principalFrom(new X500Principal("CN=node-1"));

    assertEquals("node-1", principal.name());
    assertEquals(Set.of(), principal.groups());
  }

  @Test
  void an_escaped_value_is_unescaped_rather_than_split_on_its_delimiter() {
    Principal principal =
        CertificateIdentity.principalFrom(new X500Principal("O=gimle:tenant:a\\,b,CN=w"));

    assertEquals(Set.of("gimle:tenant:a,b"), principal.groups());
  }

  @Test
  void a_subject_with_no_common_name_is_rejected() {
    assertThrows(
        IllegalStateException.class,
        () -> CertificateIdentity.principalFrom(new X500Principal("O=gimle:nodes")));
  }
}
