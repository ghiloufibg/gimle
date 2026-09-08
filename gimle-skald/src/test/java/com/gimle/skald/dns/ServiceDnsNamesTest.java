package com.gimle.skald.dns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ServiceDnsNamesTest {

  @Test
  void strips_the_zone_suffix_for_a_tenant_scoped_name() {
    Optional<String> qualified =
        ServiceDnsNames.qualifiedServiceName("orders.acme.svc.gimle.local");
    assertEquals(Optional.of("orders.acme"), qualified);
  }

  @Test
  void defaults_a_bare_short_form_name_missing_the_tenant_label_to_the_default_tenant() {
    Optional<String> qualified = ServiceDnsNames.qualifiedServiceName("orders.svc.gimle.local");
    assertEquals(Optional.of("orders.default"), qualified);
  }

  @Test
  void is_case_insensitive() {
    Optional<String> qualified = ServiceDnsNames.qualifiedServiceName("ORDERS.SVC.GIMLE.LOCAL");
    assertEquals(Optional.of("orders.default"), qualified);
  }

  @Test
  void rejects_a_name_outside_the_zone() {
    assertTrue(ServiceDnsNames.qualifiedServiceName("orders.example.com").isEmpty());
  }

  @Test
  void rejects_the_bare_zone_suffix_with_nothing_in_front_of_it() {
    assertTrue(ServiceDnsNames.qualifiedServiceName("svc.gimle.local").isEmpty());
  }
}
