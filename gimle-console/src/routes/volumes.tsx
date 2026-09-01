import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { AlertTriangle, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { PageContainer, PageHeader, StatTile } from "@/components/page-shell";
import { StatusBadge } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { fmtBytes } from "@/lib/format";
import { cn } from "@/lib/utils";
import { useVolumesStore } from "@/stores/useVolumesStore";
import type { Volume } from "@/types";
import {
  describeVolume,
  emptyListingMessage,
  filterVolumes,
  isReclaimable,
  reclaimableVolumes,
  totalUsedBytes,
  unreachableWarning,
  volumeKey,
  volumeState,
} from "./-volumes";

const DESCRIPTION = "StatefulSet persistent volumes across every node, retained orphans included.";

export const Route = createFileRoute("/volumes")({
  head: () => ({
    meta: [
      { title: "Volumes — Gimlé Console" },
      { name: "description", content: DESCRIPTION },
      { property: "og:title", content: "Volumes — Gimlé Console" },
      { property: "og:description", content: DESCRIPTION },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: VolumesPage,
});

function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="mb-3 rounded border border-status-bad/40 bg-status-bad-bg/40 px-3 py-2 text-xs text-status-bad">
      {message}
    </div>
  );
}

function UnreachableBanner({ nodes }: { nodes: string[] }) {
  return (
    <div className="mb-3 flex items-start gap-2 rounded border border-status-warn/40 bg-status-warn-bg/40 px-3 py-2 text-xs text-status-warn">
      <AlertTriangle className="mt-px h-3.5 w-3.5 shrink-0" />
      <span>{unreachableWarning(nodes)}</span>
    </div>
  );
}

/** Irreversible: the confirmation names the exact StatefulSet, index and node about to be erased. */
function DestroyVolumeButton({
  volume,
  onConfirm,
  busy,
}: {
  volume: Volume;
  onConfirm: () => void;
  busy: boolean;
}) {
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <button
          className="text-muted-foreground hover:text-status-bad disabled:pointer-events-none disabled:opacity-40"
          disabled={busy}
          aria-label={`Destroy volume ${describeVolume(volume)}`}
          title="Destroy this orphaned volume"
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Destroy {describeVolume(volume)}?</AlertDialogTitle>
          <AlertDialogDescription>
            This permanently erases {fmtBytes(volume.usedBytes)} at{" "}
            <span className="font-mono">{volume.path}</span> on node {volume.nodeId}. The data is
            not backed up and cannot be recovered — a new instance at index {volume.instanceIndex}{" "}
            would start from an empty volume.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction
            onClick={onConfirm}
            className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
          >
            Destroy volume
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function VolumesPage() {
  const volumes = useVolumesStore((s) => s.volumes);
  const unreachableNodes = useVolumesStore((s) => s.unreachableNodes);
  const loading = useVolumesStore((s) => s.loading);
  const error = useVolumesStore((s) => s.error);
  const load = useVolumesStore((s) => s.load);
  const destroy = useVolumesStore((s) => s.destroy);
  const [filter, setFilter] = useState("");
  const [destroying, setDestroying] = useState<string | null>(null);

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const shown = filterVolumes(volumes, filter);
  const orphans = reclaimableVolumes(volumes);

  async function handleDestroy(volume: Volume) {
    setDestroying(volumeKey(volume));
    try {
      await destroy(volume);
      toast.success(`Destroyed ${describeVolume(volume)}`);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setDestroying(null);
    }
  }

  return (
    <PageContainer>
      <PageHeader
        title="Volumes"
        subtitle={DESCRIPTION}
        actions={
          <Button size="sm" variant="outline" onClick={() => load()} disabled={loading}>
            <RefreshCw className={cn("mr-1.5 h-3 w-3", loading && "animate-spin")} />
            Refresh
          </Button>
        }
      />

      {error && <ErrorBanner message={error} />}
      {unreachableNodes.length > 0 && <UnreachableBanner nodes={unreachableNodes} />}

      <div className="mb-4 grid gap-3 sm:grid-cols-3">
        <StatTile label="Volumes" value={volumes.length} />
        <StatTile
          label="Reclaimable"
          value={orphans.length}
          tone={orphans.length > 0 ? "alarm" : "muted"}
          note={
            orphans.length > 0 ? (
              <span className="text-xs text-muted-foreground">
                {fmtBytes(totalUsedBytes(orphans))} retained
              </span>
            ) : undefined
          }
        />
        <StatTile label="Total on disk" value={fmtBytes(totalUsedBytes(volumes))} tone="muted" />
      </div>

      <div className="mb-3">
        <Input
          className="h-8 max-w-xs font-mono text-xs"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="Filter by set, node, tenant, path"
          aria-label="Filter volumes"
        />
      </div>

      <div className="overflow-x-auto rounded border border-border bg-card">
        <table className="w-full text-xs">
          <thead className="bg-muted/50 text-muted-foreground">
            <tr className="text-left">
              <th className="px-2 py-1.5 font-medium">StatefulSet</th>
              <th className="px-2 py-1.5 font-medium">Index</th>
              <th className="px-2 py-1.5 font-medium">Volume</th>
              <th className="px-2 py-1.5 font-medium">Tenant</th>
              <th className="px-2 py-1.5 font-medium">Node</th>
              <th className="px-2 py-1.5 font-medium text-right">Size</th>
              <th className="px-2 py-1.5 font-medium">State</th>
              <th className="px-2 py-1.5 font-medium">Path</th>
              <th className="w-10 px-2 py-1.5 font-medium"></th>
            </tr>
          </thead>
          <tbody>
            {shown.map((v) => {
              const state = volumeState(v);
              const key = volumeKey(v);
              return (
                <tr key={key} className="border-t border-border hover:bg-muted/30">
                  <td className="px-2 py-1.5 font-mono">{v.statefulSet}</td>
                  <td className="px-2 py-1.5 font-mono tabular-nums">{v.instanceIndex}</td>
                  <td className="px-2 py-1.5 font-mono">{v.volumeName}</td>
                  <td className="px-2 py-1.5 font-mono">{v.tenantId ?? "—"}</td>
                  <td className="px-2 py-1.5 font-mono">{v.nodeId}</td>
                  <td className="px-2 py-1.5 text-right font-mono tabular-nums">
                    {fmtBytes(v.usedBytes)}
                  </td>
                  <td className="px-2 py-1.5">
                    <span title={state.detail}>
                      <StatusBadge variant={state.variant}>{state.label}</StatusBadge>
                    </span>
                  </td>
                  <td className="px-2 py-1.5 font-mono text-muted-foreground" title={v.path}>
                    {v.path}
                  </td>
                  <td className="px-2 py-1.5">
                    {isReclaimable(v) ? (
                      <DestroyVolumeButton
                        volume={v}
                        busy={destroying === key}
                        onConfirm={() => handleDestroy(v)}
                      />
                    ) : (
                      <span
                        className="text-muted-foreground/40"
                        title="Only a volume nothing binds and nothing holds can be destroyed."
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </span>
                    )}
                  </td>
                </tr>
              );
            })}
            {shown.length === 0 && !loading && (
              <tr>
                <td colSpan={9} className="px-4 py-10 text-center text-muted-foreground">
                  {volumes.length === 0
                    ? emptyListingMessage(unreachableNodes)
                    : "No volume matches this filter."}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </PageContainer>
  );
}
