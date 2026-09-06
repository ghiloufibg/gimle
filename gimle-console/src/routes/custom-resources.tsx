import { createFileRoute } from "@tanstack/react-router";
import { useEffect } from "react";
import { useAutoRefresh } from "@/hooks/use-auto-refresh";
import { useCustomResourcesStore, resourceKey } from "@/stores/useCustomResourcesStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/status";
import { cn } from "@/lib/utils";
import { Puzzle, RefreshCw } from "lucide-react";
import type { CustomResourceItem, KindDefinitionSummary } from "@/types";

const DESCRIPTION =
  "Instances of cluster-defined custom kinds, with the spec authors wrote and the status their operators report.";

export const Route = createFileRoute("/custom-resources")({
  head: () => ({
    meta: [
      { title: "Custom Resources — Gimlé Console" },
      { name: "description", content: DESCRIPTION },
      { property: "og:title", content: "Custom Resources — Gimlé Console" },
      { property: "og:description", content: DESCRIPTION },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: CustomResourcesPage,
});

/**
 * Why the kind list has no rows to show. "No custom kinds defined" is a claim about the cluster,
 * and only a catalog read that actually came back empty supports it: a read still in flight, one
 * that was refused, or one that has not happened yet establishes nothing about what the cluster
 * holds, and saying otherwise sends an operator looking for a KindDefinition that is already there.
 */
export type KindListEmptyReason = "loading" | "unreadable" | "none-defined";

export function kindListEmptyReason(state: {
  loading: boolean;
  catalogLoaded: boolean;
  error: string | null;
}): KindListEmptyReason {
  if (state.loading) return "loading";
  if (state.error !== null) return "unreadable";
  return state.catalogLoaded ? "none-defined" : "loading";
}

/**
 * Walks a printColumn's dot-separated path into the resource -- `status.timesSaid` -- answering
 * null the moment any segment is missing or non-object, mirroring the CLI's own resolver: an
 * unresolved column is an empty cell, never an error.
 */
export function resolvePath(resource: CustomResourceItem, path: string): unknown {
  let current: unknown = resource as unknown;
  for (const segment of path.split(".")) {
    if (current === null || typeof current !== "object" || Array.isArray(current)) return null;
    current = (current as Record<string, unknown>)[segment];
    if (current === undefined) return null;
  }
  return current;
}

/**
 * A small YAML-flavored rendering for the detail pane -- scalars, nested objects, and lists, the
 * only shapes a schema-validated spec/status tree can hold. Deliberately not a YAML library: this
 * output is read, never parsed back.
 */
export function toYaml(value: unknown, indent = 0): string {
  const pad = "  ".repeat(indent);
  if (value === null || value === undefined) return `${pad}~`;
  if (typeof value !== "object") {
    return `${pad}${typeof value === "string" ? JSON.stringify(value) : String(value)}`;
  }
  if (Array.isArray(value)) {
    if (value.length === 0) return `${pad}[]`;
    return value
      .map((item) =>
        typeof item === "object" && item !== null
          ? `${pad}-\n${toYaml(item, indent + 1)}`
          : `${pad}- ${toYaml(item, 0)}`,
      )
      .join("\n");
  }
  const entries = Object.entries(value as Record<string, unknown>);
  if (entries.length === 0) return `${pad}{}`;
  return entries
    .map(([key, v]) =>
      typeof v === "object" && v !== null
        ? `${pad}${key}:\n${toYaml(v, indent + 1)}`
        : `${pad}${key}: ${toYaml(v, 0)}`,
    )
    .join("\n");
}

function observedGeneration(resource: CustomResourceItem): number | null {
  const value = resource.status?.["observedGeneration"];
  return typeof value === "number" ? value : null;
}

/**
 * The at-a-glance "has the operator caught up" signal: an operator echoing the generation it last
 * reconciled lets spec edits that nothing has acted on yet stand out.
 */
function CatchUpBadge({ resource }: { resource: CustomResourceItem }) {
  if (resource.status === null) {
    return <StatusBadge variant="muted">no status yet</StatusBadge>;
  }
  const observed = observedGeneration(resource);
  if (observed === null) {
    // A status without the observedGeneration convention: report presence, claim nothing more.
    return <StatusBadge variant="info">status reported</StatusBadge>;
  }
  return observed >= resource.generation ? (
    <StatusBadge variant="ok">caught up</StatusBadge>
  ) : (
    <StatusBadge variant="warn">{`behind (observed ${observed} of ${resource.generation})`}</StatusBadge>
  );
}

function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="mb-3 rounded border border-status-bad/40 bg-status-bad-bg/40 px-3 py-2 text-xs text-status-bad">
      {message}
    </div>
  );
}

function cellText(value: unknown): string {
  if (value === null || value === undefined) return "–";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function EmptyKindList({ reason }: { reason: KindListEmptyReason }) {
  if (reason === "loading") {
    return <>Reading the kind catalog…</>;
  }
  if (reason === "unreadable") {
    return <>The kind catalog couldn't be read. Retrying.</>;
  }
  return (
    <>
      No custom kinds defined. Teach the cluster one with a KindDefinition manifest via{" "}
      <span className="font-mono">gimle apply</span>.
    </>
  );
}

function KindPicker() {
  const kinds = useCustomResourcesStore((s) => s.kinds);
  const catalogLoaded = useCustomResourcesStore((s) => s.catalogLoaded);
  const loading = useCustomResourcesStore((s) => s.loading);
  const error = useCustomResourcesStore((s) => s.error);
  const selectedKindName = useCustomResourcesStore((s) => s.selectedKindName);
  const selectKind = useCustomResourcesStore((s) => s.selectKind);

  return (
    <div className="rounded border border-border bg-card">
      <div className="border-b border-border px-3 py-2 text-xs font-medium text-muted-foreground">
        Kinds
      </div>
      <ul className="max-h-[70vh] overflow-y-auto py-1">
        {kinds.map((kind) => (
          <li key={kind.kindName}>
            <button
              onClick={() => selectKind(kind.kindName)}
              className={cn(
                "flex w-full flex-col gap-0.5 px-3 py-1.5 text-left hover:bg-muted/50",
                kind.kindName === selectedKindName && "bg-primary/10",
              )}
            >
              <span
                className={cn(
                  "flex items-center gap-2 font-mono text-xs",
                  kind.kindName === selectedKindName && "text-primary",
                )}
              >
                <Puzzle className="h-3 w-3 shrink-0" />
                <span className="truncate">{kind.kindName}</span>
              </span>
              <span className="pl-5 text-[10px] text-muted-foreground">
                {kind.scope} scope
                {kind.names.plural ? ` · ${kind.names.plural}` : ""}
                {kind.names.shortNames.length > 0 ? ` · ${kind.names.shortNames.join(", ")}` : ""}
              </span>
            </button>
          </li>
        ))}
        {kinds.length === 0 && (
          <li className="px-3 py-6 text-center text-xs text-muted-foreground">
            <EmptyKindList reason={kindListEmptyReason({ loading, catalogLoaded, error })} />
          </li>
        )}
      </ul>
    </div>
  );
}

function ResourceTable({ definition }: { definition: KindDefinitionSummary }) {
  const resources = useCustomResourcesStore((s) => s.resources);
  const selectedResourceKey = useCustomResourcesStore((s) => s.selectedResourceKey);
  const selectResource = useCustomResourcesStore((s) => s.selectResource);
  const loading = useCustomResourcesStore((s) => s.loading);

  return (
    <div className="overflow-x-auto rounded border border-border bg-card">
      <table className="w-full text-xs">
        <thead className="bg-muted/50 text-muted-foreground">
          <tr className="text-left">
            <th className="px-2 py-1.5 font-medium">Name</th>
            {definition.scope === "Tenant" && <th className="px-2 py-1.5 font-medium">Tenant</th>}
            <th className="px-2 py-1.5 font-medium">Generation</th>
            {definition.printColumns.map((column) => (
              <th key={column.name} className="px-2 py-1.5 font-medium" title={column.path}>
                {column.name}
              </th>
            ))}
            <th className="px-2 py-1.5 font-medium">Operator</th>
          </tr>
        </thead>
        <tbody>
          {resources.map((resource) => {
            const key = resourceKey(resource);
            return (
              <tr
                key={key}
                onClick={() => selectResource(key === selectedResourceKey ? null : key)}
                className={cn(
                  "cursor-pointer border-t border-border hover:bg-muted/30",
                  key === selectedResourceKey && "bg-primary/10",
                )}
              >
                <td className="px-2 py-1.5 font-mono">{resource.name}</td>
                {definition.scope === "Tenant" && (
                  <td className="px-2 py-1.5 font-mono text-muted-foreground">
                    {resource.tenantId ?? "–"}
                  </td>
                )}
                <td className="px-2 py-1.5 font-mono tabular-nums">{resource.generation}</td>
                {definition.printColumns.map((column) => (
                  <td key={column.name} className="px-2 py-1.5 font-mono">
                    {cellText(resolvePath(resource, column.path))}
                  </td>
                ))}
                <td className="px-2 py-1.5">
                  <CatchUpBadge resource={resource} />
                </td>
              </tr>
            );
          })}
          {resources.length === 0 && !loading && (
            <tr>
              <td
                colSpan={4 + definition.printColumns.length}
                className="px-4 py-6 text-center text-muted-foreground"
              >
                No instances of this kind.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

function ResourceDetail({ resource }: { resource: CustomResourceItem }) {
  return (
    <div className="rounded border border-border bg-card">
      <div className="flex flex-wrap items-center gap-2 border-b border-border px-3 py-2">
        <span className="font-mono text-xs font-medium">
          {resource.kind}/{resource.name}
        </span>
        {resource.tenantId && (
          <span className="font-mono text-[10px] text-muted-foreground">
            tenant {resource.tenantId}
          </span>
        )}
        <span className="font-mono text-[10px] text-muted-foreground">
          generation {resource.generation}
        </span>
        <CatchUpBadge resource={resource} />
      </div>
      <div className="grid gap-0 md:grid-cols-2">
        <div className="border-b border-border md:border-b-0 md:border-r">
          <div className="px-3 pt-2 text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
            Spec — desired, authored
          </div>
          <pre className="overflow-x-auto px-3 py-2 font-mono text-xs leading-relaxed">
            {toYaml(resource.spec)}
          </pre>
        </div>
        <div>
          <div className="px-3 pt-2 text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
            Status — observed, operator-reported
          </div>
          <pre className="overflow-x-auto px-3 py-2 font-mono text-xs leading-relaxed">
            {resource.status === null ? "# no operator has reported yet" : toYaml(resource.status)}
          </pre>
        </div>
      </div>
    </div>
  );
}

function CustomResourcesPage() {
  const kinds = useCustomResourcesStore((s) => s.kinds);
  const selectedKindName = useCustomResourcesStore((s) => s.selectedKindName);
  const resources = useCustomResourcesStore((s) => s.resources);
  const selectedResourceKey = useCustomResourcesStore((s) => s.selectedResourceKey);
  const loading = useCustomResourcesStore((s) => s.loading);
  const error = useCustomResourcesStore((s) => s.error);
  const loadKinds = useCustomResourcesStore((s) => s.loadKinds);
  const refreshResources = useCustomResourcesStore((s) => s.refreshResources);
  const poll = useCustomResourcesStore((s) => s.poll);

  useEffect(() => {
    loadKinds();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Same auto-refresh every other list screen has: instances an operator reports on change under
  // the screen, and a read the control plane refused once is asked again rather than left standing
  // as the answer.
  useAutoRefresh(poll);

  const definition = kinds.find((kind) => kind.kindName === selectedKindName) ?? null;
  const selectedResource =
    resources.find((resource) => resourceKey(resource) === selectedResourceKey) ?? null;

  return (
    <PageContainer>
      <PageHeader
        title="Custom Resources"
        subtitle="Kinds this cluster was taught via KindDefinition manifests, and their instances — read-only; authoring stays in the CLI."
        actions={
          <Button
            size="sm"
            variant="outline"
            onClick={() => (selectedKindName ? refreshResources() : loadKinds())}
            disabled={loading}
          >
            <RefreshCw className={cn("mr-1.5 h-3 w-3", loading && "animate-spin")} />
            Refresh
          </Button>
        }
      />

      {error && <ErrorBanner message={error} />}

      <div className="grid gap-4 md:grid-cols-[minmax(200px,280px)_1fr]">
        <KindPicker />
        <div className="flex flex-col gap-4">
          {!definition ? (
            <div className="rounded border border-border bg-card px-4 py-10 text-center text-xs text-muted-foreground">
              Pick a kind to see its instances.
            </div>
          ) : (
            <>
              <ResourceTable definition={definition} />
              {selectedResource && <ResourceDetail resource={selectedResource} />}
            </>
          )}
        </div>
      </div>
    </PageContainer>
  );
}
