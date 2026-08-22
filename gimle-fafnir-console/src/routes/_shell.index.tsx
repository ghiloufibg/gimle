import { Link, createFileRoute } from "@tanstack/react-router";
import { Clock, KeyRound, ShieldCheck, Users } from "lucide-react";
import { useEffect } from "react";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { ErrorBanner } from "@/components/vault/ErrorBanner";
import { useStatusStore } from "@/stores/useStatusStore";

export const Route = createFileRoute("/_shell/")({
  head: () => ({
    meta: [
      { title: "Overview — Gimlé Fafnir Vault" },
      {
        name: "description",
        content:
          "Live status of the Fafnir secrets vault: uptime, active encryption key, transport mode and known tenants.",
      },
      { property: "og:title", content: "Overview — Gimlé Fafnir Vault" },
      {
        property: "og:description",
        content: "Live status of the Fafnir secrets vault: uptime, key id, transport and tenants.",
      },
    ],
  }),
  component: OverviewPage,
});

function formatUptime(totalSeconds: number): string {
  const days = Math.floor(totalSeconds / 86400);
  const hours = Math.floor((totalSeconds % 86400) / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  if (days > 0) return `${days}d ${hours}h ${minutes}m`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}

function Tile({
  label,
  icon: Icon,
  children,
}: {
  label: string;
  icon: typeof Clock;
  children: React.ReactNode;
}) {
  return (
    <Card className="hud-panel gap-2 rounded-sm py-4 shadow-none">
      <CardHeader className="px-4">
        <CardTitle className="hud-label flex items-center gap-1.5">
          <Icon className="size-3" />
          {label}
        </CardTitle>
      </CardHeader>
      <CardContent className="px-4">{children}</CardContent>
    </Card>
  );
}

function OverviewPage() {
  const { status, loading, error, load } = useStatusStore();

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="mx-auto max-w-5xl space-y-5">
      <div>
        <div className="hud-label">overview</div>
        <h1 className="text-xl font-semibold tracking-tight">Vault status</h1>
      </div>

      {error && <ErrorBanner message={error} />}

      {loading && !status ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-24 rounded-sm" />
          ))}
        </div>
      ) : status ? (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            <Tile label="uptime" icon={Clock}>
              <div className="font-mono text-2xl">{formatUptime(status.uptimeSeconds)}</div>
            </Tile>
            <Tile label="active key id" icon={KeyRound}>
              <div className="font-mono text-2xl">#{status.activeKeyId}</div>
            </Tile>
            <Tile label="transport" icon={ShieldCheck}>
              <div className="flex flex-col gap-1.5">
                <Badge
                  className={
                    status.transportProtocol === "TLS"
                      ? "w-fit rounded-sm border-status-ok/40 bg-status-ok-bg font-mono text-status-ok"
                      : "w-fit rounded-sm border-status-warn/40 bg-status-warn-bg font-mono text-status-warn"
                  }
                  variant="outline"
                >
                  {status.transportProtocol}
                </Badge>
                {status.transportProtocol === "PLAINTEXT" && (
                  <span className="text-xs text-status-warn">
                    no client authentication enforced
                  </span>
                )}
              </div>
            </Tile>
          </div>

          <Card className="hud-panel rounded-sm shadow-none">
            <CardHeader>
              <CardTitle className="hud-label flex items-center gap-1.5">
                <Users className="size-3" />
                tenants ({status.tenants.length})
              </CardTitle>
            </CardHeader>
            <CardContent>
              {status.tenants.length === 0 ? (
                <p className="text-xs text-muted-foreground">No tenants known to this vault.</p>
              ) : (
                <div className="flex flex-wrap gap-2">
                  {status.tenants.map((tenant) => (
                    <Link
                      key={tenant}
                      to="/secrets"
                      search={{ tenant }}
                      className="border border-border bg-secondary px-2.5 py-1 font-mono text-xs text-secondary-foreground transition-colors hover:border-primary hover:text-primary"
                    >
                      {tenant}
                    </Link>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </>
      ) : null}
    </div>
  );
}
