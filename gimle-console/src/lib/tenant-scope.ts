/**
 * Which tenant a tenant-scoped screen (Config, ConfigMaps, Secrets, SecretMaps) is looking at
 * travels in the query string rather than living only in that screen's store: without it a link to
 * one tenant's view resolves, for whoever opens it, to whatever tenant they themselves last picked
 * -- so a shared link silently shows the wrong tenant's entries rather than the ones it was sent
 * about.
 */
export function tenantScopeSearch(search: Record<string, unknown>): { tenant?: string } {
  return typeof search.tenant === "string" && search.tenant !== "" ? { tenant: search.tenant } : {};
}

/**
 * The tenant such a screen should read: the one the URL names, else the first tenant the cluster
 * reports so a bare navigation still lands on something real. A URL naming a tenant this cluster
 * doesn't have is honoured anyway -- the read that follows reports it as missing, which is the
 * truthful answer to that link, unlike silently showing a different tenant's data.
 */
export function scopedTenantId(urlTenant: string | undefined, tenantIds: string[]): string | null {
  return urlTenant ?? tenantIds[0] ?? null;
}
