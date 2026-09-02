import { useEffect, useMemo, useState } from "react";
import { Link } from "@tanstack/react-router";

import { useAutoRefresh } from "@/hooks/use-auto-refresh";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { ApplicationTile } from "@/addons/applications/components/application-tile";
import { HealthLabel, SyncLabel } from "@/addons/applications/components/status";
import {
  HEALTH_STATUSES,
  NO_FILTERS,
  SYNC_STATUSES,
  UNTENANTED,
  filterApplications,
  kindLabelsOf,
  kindSlug,
  tenantsOf,
  totalsOf,
  type ApplicationFilters,
  type HealthStatus,
  type SyncStatus,
} from "@/addons/applications/model";
import { useApplicationsStore } from "@/addons/applications/store";

type Layout = "tiles" | "table";

/** A count chip that is also the filter for what it counts -- clicking again clears it. */
function CountChip({
  label,
  count,
  active,
  tone,
  onClick,
}: {
  label: string;
  count: number;
  active: boolean;
  tone?: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-sm border px-2 py-1 font-mono text-[10px] uppercase tracking-wider transition-colors",
        active
          ? "border-primary bg-primary/10 text-primary"
          : "border-border bg-card text-muted-foreground hover:text-foreground",
      )}
    >
      <span className={cn(!active && tone)}>{label}</span>
      <span className="font-bold tabular-nums text-foreground">{count}</span>
    </button>
  );
}

function Select({
  value,
  options,
  onChange,
  label,
}: {
  value: string;
  options: string[];
  onChange: (v: string) => void;
  label: string;
}) {
  return (
    <label className="inline-flex items-center gap-1.5 font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
      {label}
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="rounded-sm border border-border bg-card px-1.5 py-1 font-mono text-[10px] text-foreground"
      >
        <option value="ALL">all</option>
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    </label>
  );
}

export function ApplicationsPage() {
  const { applications, partialFailures, loading, loaded, error, load, refresh, poll } =
    useApplicationsStore();
  const [filters, setFilters] = useState<ApplicationFilters>(NO_FILTERS);
  const [layout, setLayout] = useState<Layout>("tiles");

  useEffect(() => {
    if (!loaded) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useAutoRefresh(poll);

  const totals = useMemo(() => totalsOf(applications), [applications]);
  const shown = useMemo(() => filterApplications(applications, filters), [applications, filters]);
  const kinds = useMemo(() => kindLabelsOf(applications), [applications]);
  const tenants = useMemo(() => tenantsOf(applications), [applications]);

  const toggleHealth = (h: HealthStatus) =>
    setFilters((f) => ({ ...f, health: f.health === h ? "ALL" : h }));
  const toggleSync = (s: SyncStatus) =>
    setFilters((f) => ({ ...f, sync: f.sync === s ? "ALL" : s }));

  return (
    <PageContainer>
      <PageHeader
        eyebrow="Gimlé // Applications"
        title="Applications"
        subtitle={
          loaded
            ? `${shown.length} of ${applications.length} — every deployable resource, worst first`
            : "reading every deployable resource…"
        }
        actions={
          <div className="flex items-center gap-2">
            <div className="flex rounded-sm border border-primary/20 bg-primary/5 p-0.5">
              {(["tiles", "table"] as Layout[]).map((l) => (
                <button
                  key={l}
                  type="button"
                  onClick={() => setLayout(l)}
                  aria-pressed={layout === l}
                  className={cn(
                    "px-2.5 py-1 font-mono text-[10px] uppercase tracking-widest transition-colors",
                    layout === l
                      ? "bg-primary/20 text-primary"
                      : "text-muted-foreground hover:text-foreground",
                  )}
                >
                  {l}
                </button>
              ))}
            </div>
            <Button variant="outline" size="sm" onClick={() => refresh()} disabled={loading}>
              {loading ? "Syncing…" : "Refresh"}
            </Button>
          </div>
        }
      />

      {error && (
        <div className="mb-3 rounded border border-status-bad/40 bg-status-bad-bg/40 px-3 py-2 text-xs text-status-bad">
          {error}
        </div>
      )}
      {partialFailures.length > 0 && (
        <div className="mb-3 rounded border border-status-warn/40 bg-status-warn-bg/40 px-3 py-2 text-xs text-status-warn">
          Could not read every kind: {partialFailures.join(", ")}. Applications of those kinds are
          missing from this list rather than absent from the cluster.
        </div>
      )}

      <div className="mb-4 flex flex-wrap items-center gap-2">
        {HEALTH_STATUSES.map((h) => (
          <CountChip
            key={h}
            label={h}
            count={totals.health[h]}
            active={filters.health === h}
            onClick={() => toggleHealth(h)}
          />
        ))}
        <span className="mx-1 h-4 w-px bg-border" />
        {SYNC_STATUSES.map((s) => (
          <CountChip
            key={s}
            label={s}
            count={totals.sync[s]}
            active={filters.sync === s}
            onClick={() => toggleSync(s)}
          />
        ))}
        <Select
          label="kind"
          value={filters.kind}
          options={kinds}
          onChange={(kind) => setFilters((f) => ({ ...f, kind }))}
        />
        <Select
          label="tenant"
          value={filters.tenant}
          options={tenants}
          onChange={(tenant) => setFilters((f) => ({ ...f, tenant }))}
        />
        <Input
          value={filters.search}
          onChange={(e) => setFilters((f) => ({ ...f, search: e.target.value }))}
          placeholder="Search name, kind, module, tenant…"
          className="h-7 max-w-64 font-mono text-xs"
        />
      </div>

      {shown.length === 0 ? (
        <p className="font-mono text-xs text-muted-foreground">
          {loaded
            ? applications.length === 0
              ? "nothing is deployed on this cluster yet"
              : "no application matches these filters"
            : "loading…"}
        </p>
      ) : layout === "tiles" ? (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4">
          {shown.map((app) => (
            <ApplicationTile key={app.key} app={app} />
          ))}
        </div>
      ) : (
        <div className="overflow-x-auto rounded border border-border bg-card">
          <table className="w-full text-xs">
            <thead className="bg-muted/50 text-muted-foreground">
              <tr className="text-left">
                <th className="px-2 py-1.5 font-medium">Name</th>
                <th className="px-2 py-1.5 font-medium">Kind</th>
                <th className="px-2 py-1.5 font-medium">Tenant</th>
                <th className="px-2 py-1.5 font-medium">Module</th>
                <th className="px-2 py-1.5 font-medium">Health</th>
                <th className="px-2 py-1.5 font-medium">Sync</th>
                <th className="px-2 py-1.5 font-medium">Conditions</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((app) => (
                <tr key={app.key} className="border-t border-border hover:bg-muted/30">
                  <td className="px-2 py-1.5 font-mono">
                    <Link
                      to="/apps/$kind/$name"
                      params={{ kind: kindSlug(app), name: app.name }}
                      search={app.tenantId ? { tenant: app.tenantId } : {}}
                      className="text-primary hover:underline"
                    >
                      {app.name}
                    </Link>
                  </td>
                  <td className="px-2 py-1.5 font-mono text-muted-foreground">{app.kindLabel}</td>
                  <td className="px-2 py-1.5 font-mono">{app.tenantId ?? UNTENANTED}</td>
                  <td className="px-2 py-1.5 font-mono text-muted-foreground">
                    {app.moduleId ? `${app.moduleId.name}@${app.moduleId.version}` : "—"}
                  </td>
                  <td className="px-2 py-1.5">
                    <HealthLabel health={app.health} />
                  </td>
                  <td className="px-2 py-1.5">
                    <SyncLabel sync={app.sync} />
                  </td>
                  <td className="px-2 py-1.5 text-muted-foreground">
                    {app.conditions.length === 0 ? "—" : app.conditions[0].message}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </PageContainer>
  );
}
