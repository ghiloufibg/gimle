import { Link, createFileRoute } from "@tanstack/react-router";
import { ArrowLeft, Plug, Plus, Trash2 } from "lucide-react";
import { useEffect } from "react";
import { toast } from "sonner";

import { IvaldiWordmark } from "@/components/ivaldi/IvaldiEmblem";
import { cn } from "@/lib/utils";
import { ENVIRONMENTS, useClustersStore } from "@/stores/useClustersStore";
import { applyTheme, storedTheme } from "@/stores/useUiStore";

export const Route = createFileRoute("/clusters")({
  head: () => ({
    meta: [
      { title: "Clusters — Ivaldi" },
      {
        name: "description",
        content:
          "Configure, connect to and operate the Gimlé clusters your blueprints run on, by control plane URL.",
      },
      { property: "og:title", content: "Clusters — Ivaldi" },
      {
        property: "og:description",
        content: "Configure and connect the Gimlé control planes Ivaldi operates on.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: ClustersPage,
});

function ClustersPage() {
  const {
    clusters,
    selectedId,
    status,
    checking,
    error,
    refresh,
    add,
    patch,
    remove,
    select,
    connect,
  } = useClustersStore();

  useEffect(() => {
    applyTheme(storedTheme());
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (error) toast.error("Couldn't load clusters", { description: error });
  }, [error]);

  return (
    <main className="min-h-screen bg-background">
      <header className="flex items-center justify-between border-b border-border px-4 py-2.5">
        <div className="flex items-center gap-4">
          <Link to="/">
            <IvaldiWordmark compact />
          </Link>
          <span className="hud-label">Clusters</span>
        </div>
        <div className="flex items-center gap-2">
          <Link
            to="/"
            className="inline-flex h-7 items-center gap-1.5 rounded-sm border border-border px-2.5 font-mono text-[11px] text-foreground hover:border-primary"
          >
            <ArrowLeft className="size-3" /> Blueprints
          </Link>
          <button
            onClick={() => add(`cluster-${clusters.length + 1}`)}
            className="inline-flex h-7 items-center gap-1.5 rounded-sm bg-primary px-2.5 font-mono text-[11px] text-primary-foreground"
          >
            <Plus className="size-3" /> Add cluster
          </button>
        </div>
      </header>

      <section className="space-y-3 p-4">
        <p className="text-[11px] text-muted-foreground">
          Each cluster points at a Gimlé control plane. The selected cluster is the default target
          when you run a blueprint.
        </p>

        {clusters.length === 0 && (
          <p className="rounded-sm border border-dashed border-border p-6 text-center text-[11px] text-muted-foreground">
            No clusters yet. Add one to target your runs.
          </p>
        )}

        {clusters.map((c) => {
          const s = status[c.id];
          return (
            <article
              key={c.id}
              className={cn(
                "rounded-sm border bg-card p-3",
                c.id === selectedId ? "border-primary" : "border-border",
              )}
            >
              <div className="flex items-center justify-between gap-3">
                <label className="inline-flex items-center gap-2 font-mono text-[11px]">
                  <input
                    type="radio"
                    name="selected-cluster"
                    checked={c.id === selectedId}
                    onChange={() => select(c.id)}
                  />
                  {c.id === selectedId ? "Default target" : "Set as target"}
                </label>
                <div className="flex items-center gap-2">
                  <span
                    className={cn(
                      "rounded-sm px-2 py-0.5 font-mono text-[10px] uppercase tracking-widest",
                      s === undefined
                        ? "bg-status-muted/20 text-muted-foreground"
                        : s.ok
                          ? "bg-status-ok-bg text-status-ok"
                          : "bg-status-bad-bg text-status-bad",
                    )}
                  >
                    {checking === c.id
                      ? "connecting"
                      : s === undefined
                        ? "unknown"
                        : s.ok
                          ? `connected ${s.version ?? ""}`
                          : "unreachable"}
                  </span>
                  <button
                    onClick={() => void connect(c.id)}
                    disabled={checking === c.id}
                    className="inline-flex h-7 items-center gap-1.5 rounded-sm border border-border px-2 font-mono text-[11px] hover:border-primary disabled:opacity-40"
                  >
                    <Plug className="size-3" /> Connect
                  </button>
                  <button
                    onClick={() => remove(c.id)}
                    className="inline-flex h-7 items-center gap-1.5 rounded-sm border border-border px-2 font-mono text-[11px] text-status-bad hover:border-status-bad"
                  >
                    <Trash2 className="size-3" /> Remove
                  </button>
                </div>
              </div>

              <div className="mt-3 grid gap-3 md:grid-cols-2 lg:grid-cols-4">
                <Field label="Name">
                  <input
                    value={c.name}
                    onChange={(e) => patch(c.id, { name: e.target.value })}
                    className={inputClass}
                  />
                </Field>
                <Field label="Environment">
                  <select
                    value={c.environment}
                    onChange={(e) =>
                      patch(c.id, { environment: e.target.value as (typeof ENVIRONMENTS)[number] })
                    }
                    className={inputClass}
                  >
                    {ENVIRONMENTS.map((env) => (
                      <option key={env} value={env}>
                        {env}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="Control plane URL">
                  <input
                    value={c.controlPlaneUrl}
                    placeholder="http://127.0.0.1:8080"
                    onChange={(e) => patch(c.id, { controlPlaneUrl: e.target.value })}
                    className={inputClass}
                  />
                </Field>
                <Field label="Runner daemon URL (optional)">
                  <input
                    value={c.runnerUrl ?? ""}
                    placeholder="http://127.0.0.1:7777"
                    onChange={(e) => patch(c.id, { runnerUrl: e.target.value.trim() || null })}
                    className={inputClass}
                  />
                </Field>
                <Field label="Client certificate path (mTLS)">
                  <input
                    value={c.clientCertPath ?? ""}
                    placeholder="/etc/gimle/tls/client.crt"
                    autoComplete="off"
                    onChange={(e) => patch(c.id, { clientCertPath: e.target.value })}
                    className={inputClass}
                  />
                </Field>
                <Field label="Client key path (mTLS)">
                  <input
                    value={c.clientKeyPath ?? ""}
                    placeholder="/etc/gimle/tls/client.key"
                    autoComplete="off"
                    onChange={(e) => patch(c.id, { clientKeyPath: e.target.value })}
                    className={inputClass}
                  />
                </Field>
                <Field label="Notes" className="md:col-span-2 lg:col-span-4">
                  <input
                    value={c.description}
                    onChange={(e) => patch(c.id, { description: e.target.value })}
                    className={inputClass}
                  />
                </Field>
              </div>

              {s?.message && <p className="mt-2 text-[10px] text-status-bad">{s.message}</p>}
            </article>
          );
        })}
      </section>
    </main>
  );
}

const inputClass =
  "h-7 w-full rounded-sm border border-border bg-background px-2 font-mono text-[11px] text-foreground";

function Field({
  label,
  className,
  children,
}: {
  label: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div className={className}>
      <div className="hud-label mb-1">{label}</div>
      {children}
    </div>
  );
}
