package com.gimle.skald.dns;

import java.util.Locale;
import java.util.Optional;

/**
 * Translates between a queried DNS name and the qualified service name Skald's own directory cache
 * is keyed by. The zone is fixed: {@code <service>.<tenant>.svc.gimle.local} for a tenant-scoped
 * Service, {@code <service>.svc.gimle.local} for an untenanted one.
 *
 * <p>The control plane's {@code GET /services} contract returns service names as opaque strings
 * without a separate tenant field, so this class treats whatever that endpoint returns as exactly
 * the qualified label sequence in front of {@link #ZONE_SUFFIX} -- {@code "orders"} for an
 * untenanted Service, {@code "orders.acme"} for one scoped to tenant {@code acme} -- rather than
 * trying to split a name apart itself. That keeps the directory cache's keys and a query's derived
 * lookup key identical by construction: both are just "whatever came before the zone suffix."
 */
public final class ServiceDnsNames {

  public static final String ZONE_SUFFIX = ".svc.gimle.local";

  private ServiceDnsNames() {}

  /**
   * Strips {@link #ZONE_SUFFIX} from a queried name and returns the remaining qualified service
   * name, or {@link Optional#empty()} when the query isn't for this zone at all (wrong suffix, or
   * nothing left after stripping it).
   */
  public static Optional<String> qualifiedServiceName(String queryName) {
    String lower = queryName.toLowerCase(Locale.ROOT);
    if (!lower.endsWith(ZONE_SUFFIX)) {
      return Optional.empty();
    }
    String qualified = lower.substring(0, lower.length() - ZONE_SUFFIX.length());
    if (qualified.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(qualified);
  }
}
