package com.gimle.skald.directory;

import java.util.Locale;
import java.util.Optional;

/**
 * One entry from {@code GET /services}, as {@link ServiceCatalogClient#listServices} needs it: the
 * bare name a per-service follow-up call ({@code GET /services/{name}/endpoints}) must use, and the
 * tenant that name is scoped under, if the catalog entry carried one. In practice the control plane
 * always resolves an omitted {@code tenantId} to {@link
 * com.gimle.core.tenant.Tenant#DEFAULT_TENANT_ID} rather than leaving it unset -- a real Service is
 * never genuinely untenanted -- so {@code tenantId} being empty here reflects a catalog entry
 * missing the field entirely, not a Service living outside any tenant. {@link #qualifiedName}
 * derives the same {@code <service>[.<tenant>]} label sequence {@link
 * com.gimle.skald.dns.ServiceDnsNames} strips a queried DNS name down to -- the directory cache is
 * keyed by this, not by the bare name, since two Services of the same name in different tenants
 * would otherwise collide.
 */
public record ServiceListing(String name, Optional<String> tenantId) {

  /**
   * {@code "<name>.<tenant>"} when the catalog entry carried a tenant, {@code "<name>"} otherwise
   * -- lowercased to match {@link com.gimle.skald.dns.ServiceDnsNames#qualifiedServiceName}'s own
   * case-insensitive treatment of a queried name.
   */
  public String qualifiedName() {
    String qualified = tenantId.map(t -> name + "." + t).orElse(name);
    return qualified.toLowerCase(Locale.ROOT);
  }
}
