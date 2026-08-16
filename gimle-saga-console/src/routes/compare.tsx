import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect } from "react";
import { PageHeader } from "@/components/saga/AppShell";
import {
  Chip,
  ErrorBanner,
  HudLabel,
  HudPanel,
  LoadingBar,
  TestIdLabel,
} from "@/components/saga/primitives";
import { formatDuration, shortSha } from "@/lib/format";
import { toTestParam } from "@/lib/testId";
import { useCompareStore } from "@/stores/compareStore";
import { useRunsStore } from "@/stores/runsStore";

export const Route = createFileRoute("/compare")({
  head: () => ({
    meta: [
      { title: "Compare runs — Saga" },
      {
        name: "description",
        content:
          "Diff two Gimlé test runs: newly failing, newly passing, newly flaky tests and duration regressions over 25%.",
      },
      { property: "og:title", content: "Compare runs — Saga" },
      {
        property: "og:description",
        content:
          "Diff two Gimlé runs: newly failing, newly passing, newly flaky, duration regressions.",
      },
    ],
  }),
  component: CompareScreen,
});

function CompareScreen() {
  const { runs, loaded, load } = useRunsStore();
  const { baseRunId, headRunId, result, loading, error, setBase, setHead } = useCompareStore();

  useEffect(() => {
    if (!loaded) void load();
  }, [loaded, load]);

  return (
    <div>
      <PageHeader eyebrow="run diff" title="Compare">
        <div className="mt-4 flex flex-wrap items-end gap-4">
          <label className="block">
            <HudLabel>base run</HudLabel>
            <select
              value={baseRunId ?? ""}
              onChange={(e) => setBase(e.target.value)}
              className="mt-1 w-72 rounded-sm border border-border bg-background px-2 py-1 font-mono text-xs focus:border-primary focus:outline-none"
            >
              <option value="">select base…</option>
              {runs.map((r) => (
                <option key={r.runId} value={r.runId}>
                  {r.runId} · {r.branch}@{shortSha(r.gitSha)}
                </option>
              ))}
            </select>
          </label>
          <label className="block">
            <HudLabel>head run</HudLabel>
            <select
              value={headRunId ?? ""}
              onChange={(e) => setHead(e.target.value)}
              className="mt-1 w-72 rounded-sm border border-border bg-background px-2 py-1 font-mono text-xs focus:border-primary focus:outline-none"
            >
              <option value="">select head…</option>
              {runs.map((r) => (
                <option key={r.runId} value={r.runId}>
                  {r.runId} · {r.branch}@{shortSha(r.gitSha)}
                </option>
              ))}
            </select>
          </label>
        </div>
      </PageHeader>

      <div className="px-6 py-4">
        {error ? <ErrorBanner message={error} /> : null}
        {loading ? <LoadingBar label="diffing runs" /> : null}

        {!result && !loading ? (
          <HudPanel className="px-4 py-8 text-center">
            <HudLabel>select two runs to diff</HudLabel>
          </HudPanel>
        ) : null}

        {result ? (
          <>
            <div className="grid grid-cols-3 gap-3">
              <DiffColumn
                label="newly failing"
                tone="bad"
                items={result.newlyFailing}
                headRunId={result.head.runId}
              />
              <DiffColumn
                label="newly passing"
                tone="ok"
                items={result.newlyPassing}
                headRunId={result.head.runId}
              />
              <DiffColumn
                label="newly flaky"
                tone="warn"
                items={result.newlyFlaky}
                headRunId={result.head.runId}
              />
            </div>

            <div className="mt-4 overflow-hidden rounded-md border border-border bg-card">
              <div className="flex items-center justify-between border-b border-border px-3 py-2">
                <HudLabel>duration regressions &gt; 25%</HudLabel>
                <Chip tone={result.regressions.length ? "warn" : "muted"}>
                  {result.regressions.length}
                </Chip>
              </div>
              <table className="w-full border-collapse text-left">
                <thead>
                  <tr className="border-b border-border bg-muted/30">
                    {["test", "base", "head", "delta"].map((h) => (
                      <th key={h} className="px-3 py-1.5">
                        <HudLabel>{h}</HudLabel>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {result.regressions.map((r) => (
                    <tr key={r.testId} className="border-b border-border/60 last:border-0">
                      <td className="max-w-[34rem] truncate px-3 py-1.5">
                        <TestIdLabel testId={r.testId} />
                      </td>
                      <td className="num px-3 py-1.5 text-xs text-muted-foreground">
                        {formatDuration(r.baseMillis)}
                      </td>
                      <td className="num px-3 py-1.5 text-xs text-foreground">
                        {formatDuration(r.headMillis)}
                      </td>
                      <td className="num px-3 py-1.5 text-xs text-warn">
                        +{r.deltaPct.toFixed(0)}%
                      </td>
                    </tr>
                  ))}
                  {!result.regressions.length ? (
                    <tr>
                      <td colSpan={4} className="px-3 py-6 text-center">
                        <HudLabel>no regressions</HudLabel>
                      </td>
                    </tr>
                  ) : null}
                </tbody>
              </table>
            </div>
          </>
        ) : null}
      </div>
    </div>
  );
}

function DiffColumn({
  label,
  tone,
  items,
  headRunId,
}: {
  label: string;
  tone: "ok" | "bad" | "warn";
  items: string[];
  headRunId: string;
}) {
  const color = tone === "ok" ? "text-ok" : tone === "bad" ? "text-bad" : "text-warn";
  return (
    <HudPanel className="flex min-w-0 flex-col">
      <div className="flex items-center justify-between border-b border-border/60 px-3 py-2">
        <HudLabel>{label}</HudLabel>
        <span className={`num text-2xl leading-none font-semibold ${color}`}>{items.length}</span>
      </div>
      <ul className="max-h-80 divide-y divide-border/50 overflow-y-auto">
        {items.map((testId) => (
          <li key={testId} className="px-3 py-1.5">
            <Link
              to="/tests/$testId"
              params={{ testId: toTestParam(testId) }}
              className="block truncate hover:underline"
              title={`${testId} · ${headRunId}`}
            >
              <TestIdLabel testId={testId} />
            </Link>
          </li>
        ))}
        {!items.length ? (
          <li className="px-3 py-4">
            <HudLabel>none</HudLabel>
          </li>
        ) : null}
      </ul>
    </HudPanel>
  );
}
