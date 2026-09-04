package com.gimle.core.tenant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link Tenant#isPlatformSeeded} is what keeps a policy about "how many tenants has the operator
 * asked for" from firing against tenants nobody asked for. Its exact membership is the contract.
 */
class TenantPlatformSeededTest {

  @Test
  void the_three_tenants_the_platform_seeds_for_itself_are_recognised() {
    assertTrue(Tenant.isPlatformSeeded(Tenant.RESERVED_SYSTEM_TENANT_ID));
    assertTrue(Tenant.isPlatformSeeded(Tenant.DEFAULT_TENANT_ID));
    assertTrue(Tenant.isPlatformSeeded(Tenant.HILMIR_BOOKKEEPING_TENANT_ID));
  }

  @Test
  void an_operators_own_tenant_is_not() {
    assertFalse(Tenant.isPlatformSeeded("acme"));
    assertFalse(Tenant.isPlatformSeeded("gimle-hilmir-lookalike"));
    assertFalse(Tenant.isPlatformSeeded(""));
  }
}
