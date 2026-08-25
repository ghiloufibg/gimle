import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useArtifactsStore } from "@/stores/useArtifactsStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { StatusBadge } from "@/components/status";
import { fmtBytes, fmtRelativeTime } from "@/lib/format";
import { cn } from "@/lib/utils";
import { Copy, Package, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";

const DESCRIPTION = "Module jars pushed to the Andvari artifact registry.";

export const Route = createFileRoute("/artifacts")({
  head: () => ({
    meta: [
      { title: "Artifacts — Gimlé Console" },
      { name: "description", content: DESCRIPTION },
      { property: "og:title", content: "Artifacts — Gimlé Console" },
      { property: "og:description", content: DESCRIPTION },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: ArtifactsPage,
});

function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="mb-3 rounded border border-status-bad/40 bg-status-bad-bg/40 px-3 py-2 text-xs text-status-bad">
      {message}
    </div>
  );
}

function pushedAtTitle(epochMilli: number): string {
  return new Date(epochMilli).toLocaleString();
}

function ArtifactsPage() {
  const moduleIds = useArtifactsStore((s) => s.moduleIds);
  const selectedModuleId = useArtifactsStore((s) => s.selectedModuleId);
  const versions = useArtifactsStore((s) => s.versions);
  const loading = useArtifactsStore((s) => s.loading);
  const error = useArtifactsStore((s) => s.error);
  const loadCatalog = useArtifactsStore((s) => s.loadCatalog);
  const select = useArtifactsStore((s) => s.select);
  const remove = useArtifactsStore((s) => s.remove);
  const [filter, setFilter] = useState("");

  useEffect(() => {
    loadCatalog();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const shown = moduleIds.filter((id) => id.toLowerCase().includes(filter.trim().toLowerCase()));

  async function copyDigest(sha256: string) {
    try {
      await navigator.clipboard.writeText(sha256);
      toast.success("Digest copied");
    } catch {
      // Clipboard access is denied outside a secure context, which is a normal way to serve a
      // local control plane -- the full digest is in the cell's title attribute either way.
      toast.error("Clipboard unavailable");
    }
  }

  async function deleteVersion(moduleId: string, version: string) {
    if (
      !window.confirm(
        `Delete ${moduleId}:${version} from the registry? Nodes that have not already cached it ` +
          `will fail to resolve this coordinate.`,
      )
    ) {
      return;
    }
    try {
      await remove(moduleId, version);
      toast.success(`Deleted ${moduleId}:${version}`);
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  return (
    <PageContainer>
      <PageHeader
        title="Artifacts"
        subtitle="Immutable, content-addressed module jars in the Andvari registry. A coordinate's bytes never change — a new build is a new version."
        actions={
          <Button size="sm" variant="outline" onClick={() => loadCatalog()} disabled={loading}>
            <RefreshCw className={cn("mr-1.5 h-3 w-3", loading && "animate-spin")} />
            Refresh
          </Button>
        }
      />

      {error && <ErrorBanner message={error} />}

      <div className="grid gap-4 md:grid-cols-[minmax(180px,260px)_1fr]">
        <div className="rounded border border-border bg-card">
          <div className="border-b border-border p-2">
            <Input
              className="h-8 font-mono text-xs"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder="Filter modules"
              aria-label="Filter modules"
            />
          </div>
          <ul className="max-h-[70vh] overflow-y-auto py-1">
            {shown.map((id) => (
              <li key={id}>
                <button
                  onClick={() => select(id)}
                  className={cn(
                    "flex w-full items-center gap-2 px-3 py-1.5 text-left font-mono text-xs hover:bg-muted/50",
                    id === selectedModuleId && "bg-primary/10 text-primary",
                  )}
                >
                  <Package className="h-3 w-3 shrink-0" />
                  <span className="truncate">{id}</span>
                </button>
              </li>
            ))}
            {shown.length === 0 && (
              <li className="px-3 py-6 text-center text-xs text-muted-foreground">
                {moduleIds.length === 0 ? "No artifacts pushed." : "No module matches."}
              </li>
            )}
          </ul>
        </div>

        <div className="overflow-x-auto rounded border border-border bg-card">
          {!selectedModuleId ? (
            <div className="px-4 py-10 text-center text-xs text-muted-foreground">
              Pick a module to see its stored versions.
            </div>
          ) : (
            <table className="w-full text-xs">
              <thead className="bg-muted/50 text-muted-foreground">
                <tr className="text-left">
                  <th className="px-2 py-1.5 font-medium">Version</th>
                  <th className="px-2 py-1.5 font-medium">Kind</th>
                  <th className="px-2 py-1.5 font-medium">SHA-256</th>
                  <th className="px-2 py-1.5 font-medium">Size</th>
                  <th className="px-2 py-1.5 font-medium">Pushed</th>
                  <th className="px-2 py-1.5 font-medium">By</th>
                  <th className="w-10 px-2 py-1.5 font-medium"></th>
                </tr>
              </thead>
              <tbody>
                {versions.map((v) => (
                  <tr key={v.version} className="border-t border-border hover:bg-muted/30">
                    <td className="px-2 py-1.5">
                      <StatusBadge variant="info" className="font-mono normal-case">
                        {v.version}
                      </StatusBadge>
                    </td>
                    <td className="px-2 py-1.5">
                      <span
                        className="font-mono text-muted-foreground"
                        title={
                          v.kind === "BUNDLE"
                            ? "A zipped multi-file application with its own entrypoint"
                            : "A single module or vessel jar"
                        }
                      >
                        {v.kind === "BUNDLE" ? "bundle" : "jar"}
                      </span>
                    </td>
                    <td className="px-2 py-1.5">
                      <span className="font-mono text-muted-foreground" title={v.sha256}>
                        {v.sha256.slice(0, 12)}…
                      </span>
                      <button
                        onClick={() => copyDigest(v.sha256)}
                        className="ml-2 inline-flex items-center text-muted-foreground hover:text-foreground"
                        aria-label={`Copy digest for ${v.version}`}
                        title="Copy full digest"
                      >
                        <Copy className="h-3 w-3" />
                      </button>
                    </td>
                    <td className="px-2 py-1.5 font-mono tabular-nums">{fmtBytes(v.sizeBytes)}</td>
                    <td
                      className="px-2 py-1.5 text-muted-foreground"
                      title={pushedAtTitle(v.pushedAtEpochMilli)}
                    >
                      {fmtRelativeTime(new Date(v.pushedAtEpochMilli).toISOString())}
                    </td>
                    <td className="px-2 py-1.5 font-mono">{v.pushedBy}</td>
                    <td className="px-2 py-1.5">
                      <button
                        onClick={() => deleteVersion(v.moduleId, v.version)}
                        className="text-muted-foreground hover:text-status-bad"
                        aria-label={`Delete ${v.moduleId}:${v.version}`}
                        title="Delete this version"
                      >
                        <Trash2 className="h-3 w-3" />
                      </button>
                    </td>
                  </tr>
                ))}
                {versions.length === 0 && !loading && (
                  <tr>
                    <td colSpan={6} className="px-4 py-6 text-center text-muted-foreground">
                      No versions stored for this module.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </PageContainer>
  );
}
