package com.gimle.skald.dns;

import com.gimle.core.tenant.Tenant;
import java.util.Locale;
import java.util.Optional;

/**
 * Translates between a queried DNS name and the qualified service name Skald's own directory cache
 * is keyed by. The zone is fixed: {@code <service>.<tenant>.svc.gimle.local} for a Service, with
 * the tenant label optional in a query -- {@code <service>.svc.gimle.local} resolves the same
 * Service when it belongs to {@link Tenant#DEFAULT_TENANT_ID}.
 *
 * <p>The control plane's {@code GET /services} contract returns service names as opaque strings
 * without a separate tenant field, so this class treats whatever that endpoint returns as exactly
 * the qualified label sequence in front of {@link #ZONE_SUFFIX} -- {@code "orders.default"} for a
 * Service in the default tenant, {@code "orders.acme"} for one scoped to tenant {@code acme} --
 * every Service always has an explicit tenant, default included, so the directory cache never holds
 * a bare, tenant-less key. A query omitting the tenant label is defaulted the same way here before
 * the lookup, matching how the control plane itself resolves an omitted {@code tenantId}.
 */
public final class ServiceDnsNames {

  public static final String ZONE_SUFFIX = ".svc.gimle.local";

  private ServiceDnsNames() {}

  /**
   * Strips {@link #ZONE_SUFFIX} from a queried name and returns the remaining qualified service
   * name, or {@link Optional#empty()} when the query isn't for this zone at all (wrong suffix, or
   * nothing left after stripping it). A bare, single-label name -- no tenant label at all -- is
   * defaulted to {@link Tenant#DEFAULT_TENANT_ID}, the same tenant an omitted {@code tenantId}
   * resolves to everywhere else in the platform; a name that already carries one or more further
   * labels (an explicit tenant, or a dashed-endpoint prefix ahead of one) is returned unchanged.
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
    if (!qualified.contains(".")) {
      qualified = qualified + "." + Tenant.DEFAULT_TENANT_ID;
    }
    return Optional.of(qualified);
  }
}
