import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect } from "react";
import { Waypoints } from "lucide-react";

import { useAutoRefresh } from "@/hooks/use-auto-refresh";
import { PageContainer, PageHeader, Panel, StatTile } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
import type { NavEntry } from "@/lib/nav";
import { routePathDisplay, routeTarget } from "@/lib/gateway-routes";
import {
  GATEWAY_CONFIG_TENANT,
  GATEWAY_DAEMONSET_NAME,
  GATEWAY_ROUTES_KEY,
  readyInstances,
  useGatewayStore,
  type GatewayRouteRow,
  type RouteResolution,
} from "@/stores/useGatewayStore";

/** This screen's own sidebar link, declared here so deleting this file removes the link with it. */
export const navEntry: NavEntry = {
  title: "Gateway",
  url: "/gateway",
  icon: Waypoints,
  group: "Edge",
};

export const Route = createFileRoute("/gateway")({
  head: () => ({
    meta: [
      { title: "Gateway — Gimlé Console" },
      {
        name: "description",
        content:
          "The edge gateway's declared route table and what each route currently resolves to.",
      },
      { property: "og:title", content: "Gateway — Gimlé Console" },
      {
        property: "og:description",
        content:
          "The edge gateway's declared route table and what each route currently resolves to.",
      },
    ],
  }),
  component: GatewayPage,
});

const KIND_STYLES: Record<string, string> = {
  SERVICE: "border-status-ok/40 bg-status-ok-bg/40 text-status-ok",
  VESSEL: "border-status-info/40 bg-status-info-bg/40 text-status-info",
  FABRIC: "border-primary/40 bg-primary/10 text-primary",
};

function KindPill({ kind, broken }: { kind: string; broken: boolean }) {
  const style = broken
    ? "border-status-bad/40 bg-status-bad-bg/40 text-status-bad"
    : (KIND_STYLES[kind] ?? "border-border bg-muted text-muted-foreground");
  return (
    <span
      className={`inline-block rounded-sm border px-1.5 py-0.5 font-mono text-[9px] font-bold uppercase tracking-wider ${style}`}
    >
      {kind}
    </span>
  );
}

function Resolution({ resolution }: { resolution: RouteResolution }) {
  switch (resolution.status) {
    case "live":
      return <span className="text-status-ok">{resolution.endpointCount} live</span>;
    case "empty":
      return <span className="text-status-warn">0 — {resolution.detail}</span>;
    case "missing":
      return <span className="text-status-bad">0 — {resolution.detail}</span>;
    case "unresolvable":
      return <span className="text-muted-foreground">— {resolution.detail}</span>;
    case "unknown":
      return <span className="text-status-warn">unknown — {resolution.detail}</span>;
  }
}

/** A route nothing can currently be served from -- the finding this screen exists for. */
function isBroken(row: GatewayRouteRow): boolean {
  return row.resolution.status === "missing" || row.resolution.status === "empty";
}

function GatewayPage() {
  const {
    rows,
    parseErrors,
    routesConfigured,
    listenPort,
    instances,
    deployed,
    loading,
    loaded,
    error,
    load,
    refresh,
    poll,
  } = useGatewayStore();

  useEffect(() => {
    if (!loaded) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useAutoRefresh(poll);

  const ready = readyInstances(instances);
  const broken = rows.filter(isBroken);
  const hostConstrained = rows.filter((r) => r.route.host !== null);

  return (
    <PageContainer>
      <PageHeader
        eyebrow="Gimlé // Edge"
        title="Gateway"
        subtitle={
          <>
            Route table read from <span className="font-mono">{GATEWAY_ROUTES_KEY}</span> under
            tenant <span className="font-mono">{GATEWAY_CONFIG_TENANT}</span>, resolved against the
            control plane's own live endpoints.
          </>
        }
        actions={
          <Button variant="outline" size="sm" onClick={() => refresh()} disabled={loading}>
            Refresh
          </Button>
        }
      />

      {error && (
        <div className="mb-4 rounded border border-status-bad/40 bg-status-bad-bg/40 px-3 py-2 text-xs text-status-bad">
          {error}
        </div>
      )}

      <div className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatTile
          label="Instances"
          value={deployed ? `${ready.length}/${instances.length}` : "—"}
          note={
            <span className="text-xs text-muted-foreground">
              {deployed ? "edge nodes ready" : "not deployed"}
            </span>
          }
          tone={deployed && ready.length < instances.length ? "alarm" : "default"}
        />
        <StatTile
          label="Routes"
          value={rows.length}
          note={
            <span className="text-xs text-muted-foreground">
              {hostConstrained.length} host-constrained
            </span>
          }
        />
        <StatTile
          label="Unresolvable"
          value={broken.length}
          note={<span className="text-xs text-muted-foreground">no live endpoint</span>}
          tone={broken.length > 0 ? "alarm" : "muted"}
        />
        <StatTile
          label="Listening port"
          value={listenPort ?? "—"}
          note={<span className="text-xs text-muted-foreground">gateway.port</span>}
          tone="muted"
        />
      </div>

      {loaded && !deployed && (
        <div className="mb-4 rounded border border-border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
          No <span className="font-mono">{GATEWAY_DAEMONSET_NAME}</span> DaemonSet is deployed on
          this cluster. The route table below, if any, is configured but unserved.
        </div>
      )}

      {loaded && routesConfigured === false && (
        <div className="mb-4 rounded border border-status-warn/40 bg-status-warn-bg/40 px-3 py-2 text-xs text-status-warn">
          Tenant <span className="font-mono">{GATEWAY_CONFIG_TENANT}</span> carries no{" "}
          <span className="font-mono">{GATEWAY_ROUTES_KEY}</span> key, so every request reaching a
          gateway instance answers 404. Write one on the{" "}
          <Link to="/config" className="underline">
            Config
          </Link>{" "}
          screen.
        </div>
      )}

      {parseErrors.length > 0 && (
        <div className="mb-4 rounded border border-status-bad/40 bg-status-bad-bg/40 px-3 py-2 text-xs text-status-bad">
          <p className="mb-1 font-medium">
            {parseErrors.length} line{parseErrors.length === 1 ? "" : "s"} the gateway rejects — it
            refuses the whole table on any one of these, so no route is served until they are fixed.
          </p>
          <ul className="space-y-0.5 font-mono">
            {parseErrors.map((e) => (
              <li key={e.line}>
                line {e.line}: {e.message} — <span className="opacity-70">{e.text}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <Panel
        title="Route table"
        aside={
          <span className="font-mono text-[10px] text-muted-foreground">
            {GATEWAY_ROUTES_KEY} @ {GATEWAY_CONFIG_TENANT}
          </span>
        }
        className="mb-6"
      >
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead className="bg-muted/50 text-muted-foreground">
              <tr className="text-left">
                <th className="px-2 py-1.5 font-medium">Kind</th>
                <th className="px-2 py-1.5 font-medium">Host</th>
                <th className="px-2 py-1.5 font-medium">Path</th>
                <th className="px-2 py-1.5 font-medium">Target</th>
                <th className="px-2 py-1.5 font-medium">Endpoints</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.route.line} className="border-t border-border hover:bg-muted/30">
                  <td className="px-2 py-1.5">
                    <KindPill kind={row.route.kind} broken={isBroken(row)} />
                  </td>
                  <td className="px-2 py-1.5 font-mono">
                    {row.route.host ?? <span className="text-muted-foreground">any</span>}
                  </td>
                  <td className="px-2 py-1.5 font-mono">{routePathDisplay(row.route)}</td>
                  <td className="px-2 py-1.5 font-mono">{routeTarget(row.route)}</td>
                  <td className="px-2 py-1.5 font-mono">
                    <Resolution resolution={row.resolution} />
                  </td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-8 text-center text-muted-foreground">
                    {loading && !loaded ? "Loading…" : "No routes declared."}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </Panel>

      <Panel
        title="Instances"
        aside={
          <span className="font-mono text-[10px] text-muted-foreground">
            {GATEWAY_DAEMONSET_NAME}
          </span>
        }
      >
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead className="bg-muted/50 text-muted-foreground">
              <tr className="text-left">
                <th className="px-2 py-1.5 font-medium">Node</th>
                <th className="px-2 py-1.5 font-medium">Lifecycle</th>
                <th className="px-2 py-1.5 font-medium">Alive</th>
                <th className="px-2 py-1.5 font-medium">Ready</th>
                <th className="px-2 py-1.5 font-medium text-right">Requests/s</th>
                <th className="px-2 py-1.5 font-medium text-right">Errors/s</th>
              </tr>
            </thead>
            <tbody>
              {instances.map((i) => (
                <tr key={i.nodeId} className="border-t border-border hover:bg-muted/30">
                  <td className="px-2 py-1.5 font-mono">
                    <Link
                      to="/nodes/$nodeId"
                      params={{ nodeId: i.nodeId }}
                      className="text-primary hover:underline"
                    >
                      {i.nodeId}
                    </Link>
                  </td>
                  <td className="px-2 py-1.5 font-mono">{i.observation.lifecycleState}</td>
                  <td
                    className={`px-2 py-1.5 font-mono ${i.observation.alive ? "text-status-ok" : "text-status-bad"}`}
                  >
                    {i.observation.alive ? "yes" : "no"}
                  </td>
                  <td
                    className={`px-2 py-1.5 font-mono ${i.observation.ready ? "text-status-ok" : "text-status-warn"}`}
                  >
                    {i.observation.ready ? "yes" : "no"}
                  </td>
                  <td className="px-2 py-1.5 text-right font-mono">
                    {i.observation.requestRatePerSecond.toFixed(2)}
                  </td>
                  <td className="px-2 py-1.5 text-right font-mono">
                    {i.observation.errorRatePerSecond.toFixed(2)}
                  </td>
                </tr>
              ))}
              {instances.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-4 py-8 text-center text-muted-foreground">
                    {loading && !loaded ? "Loading…" : "No gateway instances placed."}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </Panel>

      <p className="mt-4 text-xs text-muted-foreground">
        Which route table revision each instance has actually applied is not shown: only a gateway
        instance knows that, and it exports no such reading yet. Everything above is what the
        gateway was told, read from the same config key it reads.
      </p>
    </PageContainer>
  );
}
