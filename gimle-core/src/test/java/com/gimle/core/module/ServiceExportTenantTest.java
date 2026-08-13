package com.gimle.core.module;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@code allowedTenantIds}/{@code permitsTenant}. */
class ServiceExportTenantTest {

  private static final Version V1 = Version.parse("1.0.0");

  @Test
  void an_unrestricted_export_permits_any_tenant() {
    ServiceExport export = new ServiceExport("com.example.Greeter", V1);

    assertTrue(export.permitsTenant(Optional.of("tenant-a")));
    assertTrue(export.permitsTenant(Optional.empty()));
  }

  @Test
  void a_restricted_export_permits_only_listed_tenants() {
    ServiceExport export =
        new ServiceExport("com.example.Greeter", V1, Optional.of(Set.of("tenant-a")));

    assertTrue(export.permitsTenant(Optional.of("tenant-a")));
    assertFalse(export.permitsTenant(Optional.of("tenant-b")));
  }

  @Test
  void a_restricted_export_never_permits_an_untenanted_caller() {
    ServiceExport export =
        new ServiceExport("com.example.Greeter", V1, Optional.of(Set.of("tenant-a")));

    assertFalse(export.permitsTenant(Optional.empty()));
  }

  @Test
  void a_present_but_empty_allow_list_permits_no_one() {
    ServiceExport export = new ServiceExport("com.example.Greeter", V1, Optional.of(Set.of()));

    assertFalse(export.permitsTenant(Optional.of("tenant-a")));
    assertFalse(export.permitsTenant(Optional.empty()));
  }
}
