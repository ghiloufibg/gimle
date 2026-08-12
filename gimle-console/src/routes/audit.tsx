import { createFileRoute } from "@tanstack/react-router";
import { useEffect } from "react";

import { PageContainer, PageHeader, Panel, StatTile } from "@/components/page-shell";
import { fmtRelativeTime } from "@/lib/format";
import { cn } from "@/lib/utils";
import { useAuditStore } from "@/stores/useAuditStore";

export const Route = createFileRoute("/audit")({
  head: () => ({
    meta: [
      { title: "Audit Trail — Gimlé Console" },
      {
        name: "description",
        content: "Filterable audit trail of principal, resource, tenant and allow/deny decisions.",
      },
      { property: "og:title", content: "Audit Trail — Gimlé Console" },
      {
        property: "og:description",
        content: "Who did what, to which resource, in which tenant — allowed or denied.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: Audit,
});

function toLocalInput(iso?: string): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}`;
}

const VERB_TONE: Record<string, string> = {
  READ: "text-muted-foreground",
  WRITE: "text-primary",
  DELETE: "text-status-warn",
};

function Audit() {
  const items = useAuditStore((s) => s.items);
  const filter = useAuditStore((s) => s.filter);
  const loading = useAuditStore((s) => s.loading);
  const loaded = useAuditStore((s) => s.loaded);
  const error = useAuditStore((s) => s.error);
  const setFilter = useAuditStore((s) => s.setFilter);
  const search = useAuditStore((s) => s.search);
  const reset = useAuditStore((s) => s.reset);

  useEffect(() => {
    if (!loaded) search();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const denied = items.filter((e) => !e.allowed).length;
  const principals = new Set(items.map((e) => e.principal)).size;

  const inputCls =
    "h-8 w-full rounded-sm border border-primary/20 bg-background px-2 font-mono text-xs text-foreground";

  return (
    <PageContainer>
      <PageHeader
        title="Audit Trail"
        eyebrow="Gimlé // Audit"
        subtitle="Authorization decisions recorded by the control plane. Most recent first."
        actions={
          <button
            onClick={() => search()}
            className="rounded-sm border border-primary/20 bg-primary/10 px-2 py-1 font-mono text-[10px] uppercase tracking-widest text-primary transition-colors hover:bg-primary/20"
          >
            {loading ? "querying…" : "Run query"}
          </button>
        }
      />

      <div className="mb-6 grid grid-cols-2 gap-px bg-primary/10 lg:grid-cols-4">
        <StatTile label="Events" value={items.length} tone="primary" />
        <StatTile label="Denied" value={denied} tone={denied > 0 ? "alarm" : "default"} />
        <StatTile label="Principals" value={principals} />
        <StatTile label="Limit" value={filter.limit ?? 100} tone="muted" />
      </div>

      <Panel title="Filters" className="mb-6">
        <form
          className="grid grid-cols-1 gap-3 p-3 md:grid-cols-5"
          onSubmit={(e) => {
            e.preventDefault();
            search();
          }}
        >
          <label className="grid gap-1">
            <span className="hud-label text-muted-foreground">principal</span>
            <input
              className={inputCls}
              value={filter.principal ?? ""}
              onChange={(e) => setFilter({ principal: e.target.value })}
              placeholder="admin"
            />
          </label>
          <label className="grid gap-1">
            <span className="hud-label text-muted-foreground">resource</span>
            <input
              className={inputCls}
              value={filter.resource ?? ""}
              onChange={(e) => setFilter({ resource: e.target.value })}
              placeholder="deployment"
            />
          </label>
          <label className="grid gap-1">
            <span className="hud-label text-muted-foreground">tenant</span>
            <input
              className={inputCls}
              value={filter.tenant ?? ""}
              onChange={(e) => setFilter({ tenant: e.target.value })}
              placeholder="tenant-alpha"
            />
          </label>
          <label className="grid gap-1">
            <span className="hud-label text-muted-foreground">since</span>
            <input
              type="datetime-local"
              className={inputCls}
              value={toLocalInput(filter.since)}
              onChange={(e) =>
                setFilter({
                  since: e.target.value ? new Date(e.target.value).toISOString() : undefined,
                })
              }
            />
          </label>
          <label className="grid gap-1">
            <span className="hud-label text-muted-foreground">limit</span>
            <input
              type="number"
              min={1}
              className={inputCls}
              value={filter.limit ?? 100}
              onChange={(e) => setFilter({ limit: Number(e.target.value) || 100 })}
            />
          </label>
          <div className="flex items-end gap-2 md:col-span-5">
            <button
              type="submit"
              className="rounded-sm border border-primary/20 bg-primary/10 px-2 py-1 font-mono text-[10px] uppercase tracking-widest text-primary hover:bg-primary/20"
            >
              apply filters
            </button>
            <button
              type="button"
              onClick={() => reset()}
              className="rounded-sm border border-primary/20 bg-background px-2 py-1 font-mono text-[10px] uppercase tracking-widest text-muted-foreground hover:text-foreground"
            >
              clear
            </button>
          </div>
        </form>
      </Panel>

      <Panel
        title="Events"
        aside={<span className="hud-label text-muted-foreground">{items.length} rows</span>}
      >
        <div className="max-h-[600px] overflow-auto">
          <table className="w-full border-collapse text-left">
            <thead className="sticky top-0 bg-background/95 backdrop-blur">
              <tr className="border-b border-primary/10">
                {["when", "principal", "verb", "resource", "target", "tenant", "decision"].map((h) => (
                  <th key={h} className="px-3 py-2">
                    <span className="hud-label text-muted-foreground">{h}</span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {items.map((e) => (
                <tr
                  key={e.id}
                  className={cn(
                    "border-b border-primary/5 border-l-2 hover:bg-primary/5",
                    e.allowed ? "border-l-transparent" : "border-l-status-bad bg-status-bad-bg/20",
                  )}
                >
                  <td className="whitespace-nowrap px-3 py-1.5 font-mono text-[11px] tabular-nums text-muted-foreground">
                    {fmtRelativeTime(new Date(e.occurredAtEpochMilli).toISOString())}
                  </td>
                  <td className="px-3 py-1.5 font-mono text-[11px] text-signal">
                    {e.principal}
                    <span className="ml-1 text-[10px] text-muted-foreground">
                      {e.groups.join(",")}
                    </span>
                  </td>
                  <td
                    className={cn(
                      "px-3 py-1.5 font-mono text-[10px] uppercase tracking-widest",
                      VERB_TONE[e.verb] ?? "text-muted-foreground",
                    )}
                  >
                    {e.verb.toLowerCase()}
                  </td>
                  <td className="px-3 py-1.5 font-mono text-[11px] text-foreground">{e.resourceKind}</td>
                  <td className="px-3 py-1.5 font-mono text-[11px] text-muted-foreground">
                    {e.targetId ?? "—"}
                  </td>
                  <td className="px-3 py-1.5 font-mono text-[11px] text-muted-foreground">
                    {e.tenantId ?? "—"}
                  </td>
                  <td
                    className={cn(
                      "px-3 py-1.5 font-mono text-[10px] uppercase tracking-widest",
                      e.allowed ? "text-status-ok" : "text-status-bad",
                    )}
                  >
                    {e.allowed ? "allowed" : "denied"}
                  </td>
                </tr>
              ))}
              {items.length === 0 && (
                <tr>
                  <td colSpan={7} className="px-3 py-4 font-mono text-[11px] text-muted-foreground">
                    {loading ? "querying audit trail…" : error ? error : "no audit events match"}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </Panel>
    </PageContainer>
  );
}
