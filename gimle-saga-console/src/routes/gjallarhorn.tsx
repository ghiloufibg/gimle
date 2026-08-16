import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useEffect } from "react";
import { PageHeader } from "@/components/saga/AppShell";
import {
  Chip,
  ErrorBanner,
  HudLabel,
  HudPanel,
  LoadingBar,
  OutcomeStrip,
  TestIdLabel,
} from "@/components/saga/primitives";
import type { FlakyWindow } from "@/domain/types";
import { formatDate, relativeTime } from "@/lib/format";
import { toTestParam } from "@/lib/testId";
import { useFlakyStore } from "@/stores/flakyStore";

export const Route = createFileRoute("/gjallarhorn")({
  head: () => ({
    meta: [
      { title: "Gjallarhorn — flake scoreboard" },
      {
        name: "description",
        content:
          "Ranked flake scoreboard for Gimlé: scores, flake rates, last-30-run outcome strips, quarantine state and flake budget.",
      },
      { property: "og:title", content: "Gjallarhorn — flake scoreboard" },
      {
        property: "og:description",
        content:
          "Ranked flake scores, rates, outcome strips and quarantine state across Gimlé modules.",
      },
    ],
  }),
  component: GjallarhornScreen,
});

const WINDOWS: FlakyWindow[] = [7, 30, 90];

function GjallarhornScreen() {
  const { board, filter, loading, error, load, setFilter } = useFlakyStore();
  const navigate = useNavigate();

  useEffect(() => {
    if (!board) void load();
  }, [board, load]);

  const summary = board?.summary;

  return (
    <div>
      <PageHeader eyebrow="flake scoreboard" title="Gjallarhorn">
        <div className="mt-4 flex flex-wrap items-end gap-4">
          <label className="block">
            <HudLabel>module</HudLabel>
            <select
              value={filter.module}
              onChange={(e) => setFilter({ module: e.target.value })}
              className="mt-1 rounded-sm border border-border bg-background px-2 py-1 font-mono text-xs focus:border-primary focus:outline-none"
            >
              <option value="all">all modules</option>
              {(board?.modules ?? []).map((m) => (
                <option key={m} value={m}>
                  {m}
                </option>
              ))}
            </select>
          </label>

          <div>
            <HudLabel>window</HudLabel>
            <div className="mt-1 flex overflow-hidden rounded-sm border border-border">
              {WINDOWS.map((w) => (
                <button
                  key={w}
                  type="button"
                  onClick={() => setFilter({ window: w })}
                  className={`num border-r border-border px-2 py-1 text-xs last:border-0 transition-colors ${
                    filter.window === w
                      ? "bg-primary text-primary-foreground"
                      : "bg-background text-muted-foreground hover:bg-accent"
                  }`}
                >
                  {w}d
                </button>
              ))}
            </div>
          </div>

          <label className="flex items-center gap-2 pb-1">
            <input
              type="checkbox"
              checked={filter.quarantinedOnly}
              onChange={(e) => setFilter({ quarantinedOnly: e.target.checked })}
              className="h-3 w-3 accent-[var(--primary)]"
            />
            <HudLabel>quarantined only</HudLabel>
          </label>
        </div>
      </PageHeader>

      <div className="px-6 py-4">
        {error ? <ErrorBanner message={error} /> : null}

        <div className="grid grid-cols-3 gap-3">
          <HudPanel className="px-3 py-3">
            <HudLabel>active flaky tests</HudLabel>
            <div className="num mt-1 text-3xl leading-none font-semibold text-warn">
              {summary?.activeFlaky ?? "—"}
            </div>
            <div className="num mt-1 text-[11px] text-muted-foreground">
              {board?.entries.filter((e) => e.quarantined).length ?? 0} quarantined
            </div>
          </HudPanel>
          <HudPanel className="px-3 py-3">
            <HudLabel>flake budget used</HudLabel>
            <div
              className={`num mt-1 text-3xl leading-none font-semibold ${
                (summary?.budgetUsedPct ?? 0) > 100 ? "text-bad" : "text-signal"
              }`}
            >
              {summary ? `${summary.budgetUsedPct}%` : "—"}
            </div>
            <div className="num mt-1 text-[11px] text-muted-foreground">
              {summary ? `${summary.budgetSpent} / ${summary.budgetAllowance} retries` : ""}
            </div>
          </HudPanel>
          <HudPanel className="min-w-0 px-3 py-3">
            <HudLabel>worst offender</HudLabel>
            <div className="num mt-1 truncate text-sm text-foreground">
              {summary?.worstOffender?.testId.split(":")[1] ?? "—"}
            </div>
            <div className="num mt-1 text-[11px] text-bad">
              score {summary?.worstOffender?.score.toFixed(1) ?? "—"}
            </div>
          </HudPanel>
        </div>

        {loading && !board ? <LoadingBar label="scoring flake history" /> : null}

        <div className="mt-4 overflow-hidden rounded-md border border-border bg-card">
          <table className="w-full border-collapse text-left">
            <thead>
              <tr className="border-b border-border bg-muted/40">
                {[
                  "score",
                  "test",
                  "module",
                  "flake rate",
                  "occurrences",
                  "last 30 runs",
                  "state",
                  "first seen",
                  "last seen",
                ].map((h) => (
                  <th key={h} className="px-3 py-2">
                    <HudLabel>{h}</HudLabel>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {(board?.entries ?? []).map((entry) => (
                <tr
                  key={entry.testId}
                  onClick={() =>
                    navigate({
                      to: "/tests/$testId",
                      params: { testId: toTestParam(entry.testId) },
                    })
                  }
                  className="cursor-pointer border-b border-border/60 transition-colors last:border-0 hover:bg-accent/40"
                >
                  <td className="px-3 py-2">
                    <span
                      className={`num text-lg font-semibold ${
                        entry.score > 55
                          ? "text-bad"
                          : entry.score > 30
                            ? "text-warn"
                            : "text-foreground"
                      }`}
                    >
                      {entry.score.toFixed(1)}
                    </span>
                  </td>
                  <td className="max-w-[26rem] truncate px-3 py-2">
                    <TestIdLabel testId={entry.testId} />
                  </td>
                  <td className="px-3 py-2">
                    <Chip tone="info">{entry.module.replace("gimle-", "")}</Chip>
                  </td>
                  <td className="num px-3 py-2 text-xs text-warn">
                    {(entry.flakeRate * 100).toFixed(1)}%
                  </td>
                  <td className="num px-3 py-2 text-xs text-muted-foreground">
                    {entry.occurrences}
                    <span className="text-muted-foreground/60"> / {entry.runsSeen}</span>
                  </td>
                  <td className="px-3 py-2">
                    <OutcomeStrip history={entry.history} />
                  </td>
                  <td className="px-3 py-2">
                    {entry.quarantined ? <Chip tone="muted">QUARANTINED</Chip> : null}
                  </td>
                  <td className="num px-3 py-2 text-[11px] text-muted-foreground">
                    {formatDate(entry.firstSeen)}
                  </td>
                  <td className="num px-3 py-2 text-[11px] text-muted-foreground">
                    {relativeTime(entry.lastSeen)}
                  </td>
                </tr>
              ))}
              {!loading && !(board?.entries ?? []).length ? (
                <tr>
                  <td colSpan={9} className="px-3 py-8 text-center">
                    <HudLabel>no flaky tests in window</HudLabel>
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
