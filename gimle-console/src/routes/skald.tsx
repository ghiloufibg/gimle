import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { Globe, Trash2 } from "lucide-react";

import { useAutoRefresh } from "@/hooks/use-auto-refresh";
import { PageContainer, PageHeader, Panel, StatTile } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type { NavEntry } from "@/lib/nav";
import { SKALD_ZONE_SUFFIX } from "@/lib/skald-dns";
import { useSkaldStore, type SkaldResponder } from "@/stores/useSkaldStore";

/** This screen's own sidebar link, declared here so deleting this file removes the link with it. */
export const navEntry: NavEntry = {
  title: "Skald DNS",
  url: "/skald",
  icon: Globe,
  group: "Edge",
};

const DESCRIPTION = "Which svc.gimle.local names resolve right now, and to how many addresses.";

export const Route = createFileRoute("/skald")({
  head: () => ({
    meta: [
      { title: "Skald DNS — Gimlé Console" },
      { name: "description", content: DESCRIPTION },
      { property: "og:title", content: "Skald DNS — Gimlé Console" },
      { property: "og:description", content: DESCRIPTION },
    ],
  }),
  component: SkaldPage,
});

/** A replica whose directory has gone stale is answering SERVFAIL rather than serving old records,
 * so staleness is a hard signal, not a cosmetic one. */
function responderTone(r: SkaldResponder): "ok" | "warn" | "bad" | "unknown" {
  if (r.error !== null) return "bad";
  if (r.stalenessSeconds === null) return "unknown";
  if ((r.consecutiveFailures ?? 0) > 0 || r.stalenessSeconds > 30) return "warn";
  return "ok";
}

const TONE_CLASS: Record<string, string> = {
  ok: "text-status-ok",
  warn: "text-status-warn",
  bad: "text-status-bad",
  unknown: "text-muted-foreground",
};

function SkaldPage() {
  const {
    names,
    responders,
    loading,
    loaded,
    error,
    load,
    refresh,
    poll,
    addResponder,
    removeResponder,
  } = useSkaldStore();
  const [address, setAddress] = useState("");

  useEffect(() => {
    if (!loaded) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useAutoRefresh(poll);

  const empty = names.filter((n) => n.addressCount === 0 && n.unreadable === null);
  const tenants = new Set(names.map((n) => n.tenantId ?? "—"));
  const worst = responders.reduce<number | null>(
    (max, r) => (r.stalenessSeconds === null ? max : Math.max(max ?? 0, r.stalenessSeconds)),
    null,
  );

  return (
    <PageContainer>
      <PageHeader
        eyebrow="Gimlé // Edge"
        title="Skald DNS"
        subtitle={
          <>
            Names computed from the same <span className="font-mono">/services</span> reads a Skald
            replica polls, in the <span className="font-mono">{SKALD_ZONE_SUFFIX.slice(1)}</span>{" "}
            zone.
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
          label="Responders"
          value={responders.length}
          note={<span className="text-xs text-muted-foreground">tracked replicas</span>}
          tone={responders.length === 0 ? "muted" : "default"}
        />
        <StatTile
          label="Names served"
          value={names.length}
          note={
            <span className="text-xs text-muted-foreground">
              across {tenants.size} tenant{tenants.size === 1 ? "" : "s"}
            </span>
          }
        />
        <StatTile
          label="Empty answers"
          value={empty.length}
          note={<span className="text-xs text-muted-foreground">name with no address</span>}
          tone={empty.length > 0 ? "alarm" : "muted"}
        />
        <StatTile
          label="Directory age"
          value={worst === null ? "—" : `${Math.round(worst)}s`}
          note={<span className="text-xs text-muted-foreground">worst responder</span>}
          tone={worst !== null && worst > 30 ? "alarm" : "muted"}
        />
      </div>

      <Panel title="Resolvable names" className="mb-6">
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead className="bg-muted/50 text-muted-foreground">
              <tr className="text-left">
                <th className="px-2 py-1.5 font-medium">Name</th>
                <th className="px-2 py-1.5 font-medium">Tenant</th>
                <th className="px-2 py-1.5 font-medium text-right">A records</th>
                <th className="px-2 py-1.5 font-medium text-right">Port</th>
                <th className="px-2 py-1.5 font-medium">Backing</th>
              </tr>
            </thead>
            <tbody>
              {names.map((n) => (
                <tr key={n.dnsName} className="border-t border-border hover:bg-muted/30">
                  <td className="px-2 py-1.5 font-mono">{n.dnsName}</td>
                  <td className="px-2 py-1.5 font-mono">{n.tenantId ?? "—"}</td>
                  <td
                    className={`px-2 py-1.5 text-right font-mono ${
                      n.unreadable !== null
                        ? "text-muted-foreground"
                        : n.addressCount === 0
                          ? "text-status-bad"
                          : "text-status-ok"
                    }`}
                  >
                    {n.unreadable !== null ? "?" : n.addressCount}
                  </td>
                  <td className="px-2 py-1.5 text-right font-mono">{n.port ?? "—"}</td>
                  <td className="px-2 py-1.5 font-mono">
                    {n.unreadable !== null ? (
                      <span className="text-status-warn">unreadable — {n.unreadable}</span>
                    ) : n.addressCount === 0 ? (
                      <span className="text-status-bad">no ready instance</span>
                    ) : (
                      n.deploymentNames.join(", ")
                    )}
                  </td>
                </tr>
              ))}
              {names.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-8 text-center text-muted-foreground">
                    {loading && !loaded ? (
                      "Loading…"
                    ) : (
                      <>
                        No Services declared, so Skald answers nothing. Declare one on the{" "}
                        <Link to="/networking" className="underline">
                          Networking
                        </Link>{" "}
                        screen.
                      </>
                    )}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </Panel>

      <Panel
        title="Responders"
        aside={
          <form
            className="flex items-center gap-1"
            onSubmit={(e) => {
              e.preventDefault();
              addResponder(address);
              setAddress("");
            }}
          >
            <Input
              className="h-6 w-40 font-mono text-[10px]"
              placeholder="skald-host:8053"
              aria-label="Skald responder address"
              value={address}
              onChange={(e) => setAddress(e.target.value)}
            />
            <Button type="submit" variant="outline" size="sm" className="h-6 text-[10px]">
              Track
            </Button>
          </form>
        }
      >
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead className="bg-muted/50 text-muted-foreground">
              <tr className="text-left">
                <th className="px-2 py-1.5 font-medium">Address</th>
                <th className="px-2 py-1.5 font-medium text-right">Directory age</th>
                <th className="px-2 py-1.5 font-medium text-right">Failed polls</th>
                <th className="px-2 py-1.5 font-medium">Last reading</th>
                <th className="px-2 py-1.5" />
              </tr>
            </thead>
            <tbody>
              {responders.map((r) => (
                <tr key={r.address} className="border-t border-border hover:bg-muted/30">
                  <td className="px-2 py-1.5 font-mono">{r.address}</td>
                  <td
                    className={`px-2 py-1.5 text-right font-mono ${TONE_CLASS[responderTone(r)]}`}
                  >
                    {r.stalenessSeconds === null ? "—" : `${Math.round(r.stalenessSeconds)}s`}
                  </td>
                  <td
                    className={`px-2 py-1.5 text-right font-mono ${TONE_CLASS[responderTone(r)]}`}
                  >
                    {r.consecutiveFailures ?? "—"}
                  </td>
                  <td className="px-2 py-1.5 font-mono text-muted-foreground">
                    {r.error !== null
                      ? r.error
                      : r.lastReadingAt !== null
                        ? new Date(r.lastReadingAt).toLocaleString()
                        : "nothing shipped"}
                  </td>
                  <td className="px-2 py-1.5 text-right">
                    <button
                      className="text-muted-foreground hover:text-status-bad"
                      aria-label={`Stop tracking ${r.address}`}
                      onClick={() => removeResponder(r.address)}
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </td>
                </tr>
              ))}
              {responders.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-6 text-center text-muted-foreground">
                    No responders tracked. Nothing enumerates Skald replicas, so add each one's DNS
                    address to read the staleness gauges it ships.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </Panel>

      <p className="mt-4 text-xs text-muted-foreground">
        This is derived truth, not ground truth: the table above is what a replica polling a healthy
        control plane <em>should</em> answer. A replica whose own directory has diverged in a way
        its staleness gauges don't capture would still read as agreeing here — reading a replica's
        actual directory back needs a status surface Skald does not have.
      </p>
    </PageContainer>
  );
}
