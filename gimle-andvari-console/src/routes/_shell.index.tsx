import { useEffect, useMemo } from "react";
import { createFileRoute, Link } from "@tanstack/react-router";

import { HudPanel } from "@/components/HudPanel";
import { PageHeader } from "@/components/PageHeader";
import { Skeleton } from "@/components/ui/skeleton";
import { formatAbsolute, formatBytes, formatRelative, formatUptime } from "@/lib/format";
import { cn } from "@/lib/utils";
import { selectRecentPushes, useArtifactsStore } from "@/stores/artifactsStore";
import { useStatusStore } from "@/stores/statusStore";

export const Route = createFileRoute("/_shell/")({
  head: () => ({
    meta: [
      { title: "Registry status — Gimlé Andvari" },
      { name: "description", content: "Uptime, transport mode and recent pushes for Andvari." },
    ],
  }),
  component: OverviewPage,
});

function Tile({
  label,
  value,
  caption,
  tone,
}: {
  label: string;
  value: string;
  caption?: string;
  tone?: "ok" | "warn";
}) {
  return (
    <HudPanel label={label} className="flex-1">
      <p
        className={cn(
          "font-mono text-3xl font-bold tabular-nums",
          tone === "ok" && "text-status-ok",
          tone === "warn" && "text-status-warn",
        )}
      >
        {value}
      </p>
      {caption ? <p className="mt-1 text-xs text-muted-foreground">{caption}</p> : null}
    </HudPanel>
  );
}

function OverviewPage() {
  const status = useStatusStore((s) => s.status);
  const versionsByModule = useArtifactsStore((s) => s.versionsByModule);
  const recent = useMemo(() => selectRecentPushes({ versionsByModule }), [versionsByModule]);
  const loadAll = useArtifactsStore((s) => s.loadAll);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  const plaintext = status?.transportProtocol === "PLAINTEXT";

  return (
    <>
      <PageHeader
        eyebrow="overview"
        title="Registry status"
        description="Live state of the Andvari artifact store."
      />

      <div className="flex flex-col gap-4 sm:flex-row">
        {status ? (
          <>
            <Tile label="uptime" value={formatUptime(status.uptimeSeconds)} />
            <Tile
              label="transport"
              value={status.transportProtocol}
              tone={plaintext ? "warn" : "ok"}
              {...(plaintext ? { caption: "no client authentication enforced" } : {})}
            />
            <Tile label="modules" value={String(status.moduleCount)} />
          </>
        ) : (
          <>
            <Skeleton className="h-24 flex-1 rounded-sm" />
            <Skeleton className="h-24 flex-1 rounded-sm" />
            <Skeleton className="h-24 flex-1 rounded-sm" />
          </>
        )}
      </div>

      <HudPanel label="recent pushes">
        {recent.length === 0 ? (
          <p className="text-xs text-muted-foreground">No artifacts pushed yet.</p>
        ) : (
          <ul className="divide-y divide-border/60">
            {recent.map((item) => (
              <li key={`${item.moduleId}:${item.version}`}>
                <Link
                  to="/artifacts/$moduleId"
                  params={{ moduleId: item.moduleId }}
                  className="flex flex-wrap items-center justify-between gap-2 py-2.5 transition-colors hover:text-primary"
                >
                  <span className="font-mono text-xs">
                    {item.moduleId}
                    <span className="text-muted-foreground">:{item.version}</span>
                  </span>
                  <span className="flex items-center gap-4 font-mono text-[11px] text-muted-foreground">
                    <span>{formatBytes(item.sizeBytes)}</span>
                    <span>{item.pushedBy}</span>
                    <span title={formatAbsolute(item.pushedAtEpochMilli)}>
                      {formatRelative(item.pushedAtEpochMilli)}
                    </span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </HudPanel>
    </>
  );
}
