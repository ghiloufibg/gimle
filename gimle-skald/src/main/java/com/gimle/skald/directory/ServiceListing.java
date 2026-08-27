package com.gimle.skald.directory;

import java.util.Locale;
import java.util.Optional;

/**
 * One entry from {@code GET /services}, as {@link ServiceCatalogClient#listServices} needs it: the
 * bare name a per-service follow-up call ({@code GET /services/{name}/endpoints}) must use, and the
 * tenant that name is scoped under, if any. {@link #qualifiedName} derives the same {@code
 * <service>[.<tenant>]} label sequence {@link com.gimle.skald.dns.ServiceDnsNames} strips a queried
 * DNS name down to -- the directory cache is keyed by this, not by the bare name, since a
 * tenant-scoped Service's bare name alone would collide with an untenanted Service of the same
 * name.
 */
public record ServiceListing(String name, Optional<String> tenantId) {

  /**
   * {@code "<name>.<tenant>"} for a tenant-scoped Service, {@code "<name>"} for an untenanted one
   * -- lowercased to match {@link com.gimle.skald.dns.ServiceDnsNames#qualifiedServiceName}'s own
   * case-insensitive treatment of a queried name.
   */
  public String qualifiedName() {
    String qualified = tenantId.map(t -> name + "." + t).orElse(name);
    return qualified.toLowerCase(Locale.ROOT);
  }
}
