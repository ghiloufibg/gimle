import { useEffect, useState } from "react";
import { createFileRoute, Link } from "@tanstack/react-router";
import { ArrowLeft, Download, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { CopyButton } from "@/components/CopyButton";
import { HudPanel } from "@/components/HudPanel";
import { PageHeader } from "@/components/PageHeader";
import { PushArtifactDialog } from "@/components/PushArtifactDialog";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { formatAbsolute, formatBytes, formatRelative, mavenDependencySnippet } from "@/lib/format";
import { useArtifactsStore } from "@/stores/artifactsStore";

export const Route = createFileRoute("/_shell/artifacts/$moduleId")({
  head: ({ params }) => ({
    meta: [
      { title: `${params.moduleId} — Gimlé Andvari` },
      { name: "description", content: `Stored versions of ${params.moduleId}.` },
    ],
  }),
  component: ModuleDetailPage,
});

function ModuleDetailPage() {
  const { moduleId } = Route.useParams();
  const versions = useArtifactsStore((s) => s.versionsByModule[moduleId]);
  const loading = useArtifactsStore((s) => s.moduleLoading);
  const error = useArtifactsStore((s) => s.moduleError);
  const deletePending = useArtifactsStore((s) => s.deletePending);
  const loadModule = useArtifactsStore((s) => s.loadModule);
  const download = useArtifactsStore((s) => s.download);
  const remove = useArtifactsStore((s) => s.remove);
  const [pendingDelete, setPendingDelete] = useState<string | null>(null);

  useEffect(() => {
    void loadModule(moduleId);
  }, [loadModule, moduleId]);

  const sorted = [...(versions ?? [])].sort((a, b) => b.pushedAtEpochMilli - a.pushedAtEpochMilli);

  const onDownload = async (version: string, sha256: string) => {
    try {
      await download(moduleId, version, sha256);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Download failed");
    }
  };

  const onDelete = async () => {
    if (!pendingDelete) return;
    const version = pendingDelete;
    setPendingDelete(null);
    const ok = await remove(moduleId, version);
    if (ok) toast.success(`Deleted ${moduleId}:${version}`);
    else toast.error("Delete failed");
  };

  return (
    <>
      <Link
        to="/artifacts"
        className="hud-label flex w-fit items-center gap-1.5 hover:text-primary"
      >
        <ArrowLeft className="h-3 w-3" /> back to catalog
      </Link>

      <PageHeader
        eyebrow="module"
        title={moduleId}
        description="Every stored version. Artifacts are immutable once pushed."
        actions={<PushArtifactDialog defaultModuleId={moduleId} />}
      />

      {error ? (
        <p className="rounded-sm border border-status-bad/30 bg-status-bad-bg px-3 py-2 text-xs text-status-bad">
          {error}
        </p>
      ) : null}

      <HudPanel label="versions">
        {loading && sorted.length === 0 ? (
          <div className="space-y-2">
            <Skeleton className="h-8 rounded-sm" />
            <Skeleton className="h-8 rounded-sm" />
          </div>
        ) : sorted.length === 0 ? (
          <p className="text-xs text-muted-foreground">No versions stored for this module.</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="hud-label">version</TableHead>
                <TableHead className="hud-label">kind</TableHead>
                <TableHead className="hud-label">sha256</TableHead>
                <TableHead className="hud-label">size</TableHead>
                <TableHead className="hud-label">pushed</TableHead>
                <TableHead className="hud-label">by</TableHead>
                <TableHead className="hud-label text-right">actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {sorted.map((entry) => (
                <TableRow key={entry.version}>
                  <TableCell className="font-mono text-xs font-medium">{entry.version}</TableCell>
                  <TableCell
                    className="font-mono text-xs text-muted-foreground"
                    title={
                      entry.kind === "BUNDLE"
                        ? "A zipped multi-file application with its own entrypoint"
                        : "A single module or vessel jar"
                    }
                  >
                    {entry.kind === "BUNDLE" ? "bundle" : "jar"}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-1">
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <span className="font-mono text-xs text-muted-foreground">
                            {entry.sha256.slice(0, 12)}…
                          </span>
                        </TooltipTrigger>
                        <TooltipContent className="max-w-xs font-mono text-[10px] break-all">
                          {entry.sha256}
                        </TooltipContent>
                      </Tooltip>
                      <CopyButton value={entry.sha256} subject="sha256" />
                    </div>
                  </TableCell>
                  <TableCell className="font-mono text-xs">
                    {formatBytes(entry.sizeBytes)}
                  </TableCell>
                  <TableCell
                    className="font-mono text-xs text-muted-foreground"
                    title={formatAbsolute(entry.pushedAtEpochMilli)}
                  >
                    {formatRelative(entry.pushedAtEpochMilli)}
                  </TableCell>
                  <TableCell className="font-mono text-xs">{entry.pushedBy}</TableCell>
                  <TableCell>
                    <div className="flex items-center justify-end gap-0.5">
                      <Button
                        variant="ghost"
                        size="icon"
                        aria-label="Download jar"
                        className="h-7 w-7 rounded-sm"
                        onClick={() => void onDownload(entry.version, entry.sha256)}
                      >
                        <Download className="h-3.5 w-3.5" />
                      </Button>
                      <CopyButton
                        value={mavenDependencySnippet(moduleId, entry.version)}
                        subject="Maven dependency"
                      />
                      <Button
                        variant="ghost"
                        size="icon"
                        aria-label="Delete version"
                        disabled={deletePending === `${moduleId}:${entry.version}`}
                        className="h-7 w-7 rounded-sm text-status-bad hover:text-status-bad"
                        onClick={() => setPendingDelete(entry.version)}
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </HudPanel>

      <AlertDialog
        open={pendingDelete !== null}
        onOpenChange={(open) => !open && setPendingDelete(null)}
      >
        <AlertDialogContent className="rounded-sm">
          <AlertDialogHeader>
            <AlertDialogTitle>
              Delete {moduleId}:{pendingDelete}?
            </AlertDialogTitle>
            <AlertDialogDescription>
              This permanently removes the stored jar. Artifacts are otherwise immutable — the exact
              same bytes cannot be restored by re-pushing under a different sha256.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel className="rounded-sm">Cancel</AlertDialogCancel>
            <AlertDialogAction
              className="rounded-sm bg-destructive text-destructive-foreground hover:bg-destructive/90"
              onClick={() => void onDelete()}
            >
              Delete permanently
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
