import { useEffect, useMemo, useState } from "react";
import { createFileRoute, Link } from "@tanstack/react-router";
import { Search } from "lucide-react";

import { HudPanel } from "@/components/HudPanel";
import { PageHeader } from "@/components/PageHeader";
import { PushArtifactDialog } from "@/components/PushArtifactDialog";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { formatAbsolute, formatRelative } from "@/lib/format";
import { useArtifactsStore } from "@/stores/artifactsStore";

export const Route = createFileRoute("/_shell/artifacts/")({
  head: () => ({
    meta: [
      { title: "Artifacts — Gimlé Andvari" },
      { name: "description", content: "Browse and push immutable module jar artifacts." },
    ],
  }),
  component: ArtifactsPage,
});

function ArtifactsPage() {
  const [query, setQuery] = useState("");
  const catalog = useArtifactsStore((s) => s.catalog);
  const loading = useArtifactsStore((s) => s.catalogLoading);
  const error = useArtifactsStore((s) => s.error);
  const versionsByModule = useArtifactsStore((s) => s.versionsByModule);
  const loadAll = useArtifactsStore((s) => s.loadAll);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  const filtered = useMemo(
    () => catalog.filter((id) => id.toLowerCase().includes(query.trim().toLowerCase())),
    [catalog, query],
  );

  return (
    <>
      <PageHeader
        eyebrow="artifacts"
        title="Module catalog"
        description="Every module id known to this registry, with its stored versions."
        actions={<PushArtifactDialog />}
      />

      <div className="relative">
        <Search className="pointer-events-none absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="filter module ids…"
          className="rounded-sm pl-9 font-mono text-xs"
        />
      </div>

      {error ? (
        <p className="rounded-sm border border-status-bad/30 bg-status-bad-bg px-3 py-2 text-xs text-status-bad">
          {error}
        </p>
      ) : null}

      {loading && catalog.length === 0 ? (
        <div className="space-y-3">
          <Skeleton className="h-16 rounded-sm" />
          <Skeleton className="h-16 rounded-sm" />
          <Skeleton className="h-16 rounded-sm" />
        </div>
      ) : filtered.length === 0 ? (
        <HudPanel>
          <p className="text-xs text-muted-foreground">No modules match this filter.</p>
        </HudPanel>
      ) : (
        <div className="flex flex-col gap-3">
          {filtered.map((moduleId) => {
            const versions = versionsByModule[moduleId] ?? [];
            const latest = [...versions].sort(
              (a, b) => b.pushedAtEpochMilli - a.pushedAtEpochMilli,
            )[0];
            return (
              <Link
                key={moduleId}
                to="/artifacts/$moduleId"
                params={{ moduleId }}
                className="hud-panel flex flex-wrap items-center justify-between gap-3 rounded-sm px-4 py-3 transition-colors hover:border-primary/50"
              >
                <div className="min-w-0">
                  <p className="hud-label">module</p>
                  <p className="truncate font-mono text-xs">{moduleId}</p>
                </div>
                <div className="flex items-center gap-6">
                  <div>
                    <p className="hud-label">versions</p>
                    <p className="font-mono text-xs">{versions.length || "—"}</p>
                  </div>
                  <div>
                    <p className="hud-label">latest</p>
                    <p className="font-mono text-xs">{latest?.version ?? "—"}</p>
                  </div>
                  <div className="hidden sm:block">
                    <p className="hud-label">pushed</p>
                    <p
                      className="font-mono text-xs text-muted-foreground"
                      title={latest ? formatAbsolute(latest.pushedAtEpochMilli) : ""}
                    >
                      {latest ? formatRelative(latest.pushedAtEpochMilli) : "—"}
                    </p>
                  </div>
                </div>
              </Link>
            );
          })}
        </div>
      )}
    </>
  );
}
