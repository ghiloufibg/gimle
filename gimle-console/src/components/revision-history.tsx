import { useState } from "react";
import { Panel } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
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
import type { ControllerRevision } from "@/types";

/** Shared revision-history panel for Deployment/DaemonSet/StatefulSet detail pages -- all three
 * are backed by the same ControllerRevision API shape. `revisions` is expected newest-first, the
 * order the server itself returns; the newest row (index 0) has no rollback action since it's
 * already current. */
export function RevisionHistoryPanel({
  revisions,
  onRollback,
}: {
  revisions: ControllerRevision[];
  onRollback: (revision: number) => Promise<void>;
}) {
  const [pending, setPending] = useState<number | null>(null);

  if (revisions.length === 0) return null;

  async function confirm(revision: number) {
    setPending(revision);
    try {
      await onRollback(revision);
    } finally {
      setPending(null);
    }
  }

  return (
    <Panel title="Revision history" className="mb-6">
      <div className="overflow-x-auto rounded border border-border">
        <table className="w-full text-xs">
          <thead className="bg-muted/50 text-muted-foreground">
            <tr className="text-left">
              <th className="px-2 py-1.5 font-medium">Revision</th>
              <th className="px-2 py-1.5 font-medium">Created</th>
              <th className="px-2 py-1.5 font-medium">Module</th>
              <th className="px-2 py-1.5 font-medium">Rollback of</th>
              <th className="px-2 py-1.5 font-medium w-24"></th>
            </tr>
          </thead>
          <tbody>
            {revisions.map((r, i) => (
              <tr key={r.revision} className="border-t border-border">
                <td className="px-2 py-1.5 font-mono">v{r.revision}</td>
                <td className="px-2 py-1.5 font-mono text-muted-foreground">
                  {new Date(r.createdAtEpochMilli).toLocaleString()}
                </td>
                <td className="px-2 py-1.5 font-mono text-muted-foreground">
                  {r.moduleId.name}@{r.moduleId.version}
                </td>
                <td className="px-2 py-1.5 font-mono text-muted-foreground">
                  {r.rollbackOfRevision !== undefined ? `v${r.rollbackOfRevision}` : "—"}
                </td>
                <td className="px-2 py-1.5">
                  {i === 0 ? (
                    <span className="text-[10px] text-muted-foreground">current</span>
                  ) : (
                    <AlertDialog>
                      <AlertDialogTrigger asChild>
                        <Button
                          size="sm"
                          variant="outline"
                          className="h-6 px-2 text-[10px]"
                          disabled={pending !== null}
                        >
                          {pending === r.revision ? "Rolling back…" : "Roll back"}
                        </Button>
                      </AlertDialogTrigger>
                      <AlertDialogContent>
                        <AlertDialogHeader>
                          <AlertDialogTitle>Roll back to revision {r.revision}?</AlertDialogTitle>
                          <AlertDialogDescription>
                            This appends a brand-new revision matching v{r.revision}&apos;s module
                            and re-runs the full admission chain -- it never rewrites history.
                          </AlertDialogDescription>
                        </AlertDialogHeader>
                        <AlertDialogFooter>
                          <AlertDialogCancel>Cancel</AlertDialogCancel>
                          <AlertDialogAction onClick={() => confirm(r.revision)}>
                            Roll back
                          </AlertDialogAction>
                        </AlertDialogFooter>
                      </AlertDialogContent>
                    </AlertDialog>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Panel>
  );
}
