import type { Service } from "@/types";

/**
 * The DNS zone Skald answers `A` queries in. A Service's record name is the same
 * `<service>[.<tenant>]` label sequence Skald's own directory is keyed by, in front of this suffix
 * -- so a name derived here is exactly the name a resolver would ask for.
 */
export const SKALD_ZONE_SUFFIX = ".svc.gimle.local";

/** `<service>.<tenant>.svc.gimle.local` -- every Service carries an explicit tenant, default
 * included, so `tenantId` is only ever falsy here for a malformed listing.
 * Lowercased, matching Skald's own case-insensitive treatment of a queried name. */
export function skaldDnsName(service: Pick<Service, "name" | "tenantId">): string {
  const qualified = service.tenantId ? `${service.name}.${service.tenantId}` : service.name;
  return `${qualified}${SKALD_ZONE_SUFFIX}`.toLowerCase();
}

/** Micrometer gauge names `SkaldMetrics` registers -- the whole of what a replica reports. */
export const SKALD_STALENESS_METRIC = "gimle.skald.directory.staleness.seconds";
export const SKALD_FAILURES_METRIC = "gimle.skald.directory.consecutive.failures";
