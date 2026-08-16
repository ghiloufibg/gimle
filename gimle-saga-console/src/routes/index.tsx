import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useEffect } from "react";
import { PageHeader } from "@/components/saga/AppShell";
import { Chip, ErrorBanner, HudLabel, LoadingBar, StatusPill } from "@/components/saga/primitives";
import { formatTimestamp, relativeTime, runDuration, shortSha } from "@/lib/format";
import { useRunsStore } from "@/stores/runsStore";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "Runs — Saga test-run console" },
      {
        name: "description",
        content:
          "Every Gimlé test run, newest first: status, totals, flake counts and duration in one dense operator table.",
      },
      { property: "og:title", content: "Runs — Saga test-run console" },
      {
        property: "og:description",
        content: "Every Gimlé test run, newest first, with totals, flake counts and duration.",
      },
    ],
  }),
  component: RunsScreen,
});

function RunsScreen() {
  const { runs, loading, error, loaded, load, query, setQuery } = useRunsStore();
  const navigate = useNavigate();

  useEffect(() => {
    if (!loaded) void load();
  }, [loaded, load]);

  const filtered = runs.filter((r) => {
    const q = query.trim().toLowerCase();
    if (!q) return true;
    return [r.runId, r.branch, r.gitSha, r.command].join(" ").toLowerCase().includes(q);
  });

  const live = runs.filter((r) => r.status === "live").length;
  const failed = runs.filter((r) => r.status === "failed").length;

  return (
    <div>
      <PageHeader
        eyebrow="test runs"
        title="Runs"
        right={
          <div className="flex items-end gap-6">
            <div className="text-right">
              <HudLabel>live</HudLabel>
              <div className="num text-lg font-semibold text-signal">{live}</div>
            </div>
            <div className="text-right">
              <HudLabel>failing</HudLabel>
              <div className="num text-lg font-semibold text-bad">{failed}</div>
            </div>
            <div className="text-right">
              <HudLabel>indexed</HudLabel>
              <div className="num text-lg font-semibold">{runs.length}</div>
            </div>
          </div>
        }
      >
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="filter runId / branch / sha / command"
          className="mt-4 w-96 rounded-sm border border-border bg-background px-2 py-1.5 font-mono text-xs text-foreground placeholder:text-muted-foreground/70 focus:border-primary focus:outline-none"
        />
      </PageHeader>

      <div className="px-6 py-4">
        {error ? <ErrorBanner message={error} /> : null}
        {loading && !runs.length ? <LoadingBar label="reading run index" /> : null}

        <div className="overflow-hidden rounded-md border border-border bg-card">
          <table className="w-full border-collapse text-left">
            <thead>
              <tr className="border-b border-border bg-muted/40">
                {["run", "branch @ sha", "command", "status", "totals", "duration", "started"].map(
                  (h) => (
                    <th key={h} className="px-3 py-2">
                      <HudLabel>{h}</HudLabel>
                    </th>
                  ),
                )}
              </tr>
            </thead>
            <tbody>
              {filtered.map((run) => (
                <tr
                  key={run.runId}
                  onClick={() => navigate({ to: "/runs/$runId", params: { runId: run.runId } })}
                  className="cursor-pointer border-b border-border/60 transition-colors last:border-0 hover:bg-accent/40"
                >
                  <td className="px-3 py-2">
                    <span className="num text-xs font-medium text-foreground">{run.runId}</span>
                  </td>
                  <td className="px-3 py-2">
                    <span className="num text-xs text-foreground">{run.branch}</span>
                    <span className="num text-xs text-muted-foreground">
                      {" "}
                      @ {shortSha(run.gitSha)}
                    </span>
                  </td>
                  <td className="max-w-[22rem] truncate px-3 py-2">
                    <span className="num text-[11px] text-muted-foreground">{run.command}</span>
                  </td>
                  <td className="px-3 py-2">
                    <StatusPill status={run.status} />
                  </td>
                  <td className="px-3 py-2">
                    <div className="flex items-center gap-1">
                      <Chip tone="ok">{run.totals.passed} pass</Chip>
                      <Chip tone={run.totals.failed ? "bad" : "muted"}>
                        {run.totals.failed} fail
                      </Chip>
                      <Chip tone={run.totals.flaky ? "warn" : "muted"}>
                        {run.totals.flaky} flaky
                      </Chip>
                    </div>
                  </td>
                  <td className="num px-3 py-2 text-xs text-muted-foreground">
                    {runDuration(run.startedAt, run.finishedAt)}
                  </td>
                  <td className="px-3 py-2">
                    <div className="num text-xs text-foreground">{relativeTime(run.startedAt)}</div>
                    <div className="num text-[10px] text-muted-foreground">
                      {formatTimestamp(run.startedAt)}
                    </div>
                  </td>
                </tr>
              ))}
              {!loading && !filtered.length ? (
                <tr>
                  <td colSpan={7} className="px-3 py-8 text-center">
                    <HudLabel>no runs match filter</HudLabel>
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
