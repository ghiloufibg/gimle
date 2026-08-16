import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect } from "react";
import { PageHeader } from "@/components/saga/AppShell";
import {
  Chip,
  ErrorBanner,
  HudLabel,
  HudPanel,
  LoadingBar,
  OutcomeStrip,
  SectionHeader,
} from "@/components/saga/primitives";
import { formatDuration, formatTimestamp } from "@/lib/format";
import { fromTestParam } from "@/lib/testId";
import { useTestHistoryStore } from "@/stores/testHistoryStore";

export const Route = createFileRoute("/tests/$testId")({
  head: ({ params }) => {
    const testId = fromTestParam(params.testId);
    const title = `${testId.split(":")[1] ?? testId} — test history · Saga`;
    return {
      meta: [
        { title },
        {
          name: "description",
          content: `Outcome strip, duration trend and failure signatures for ${testId} across recent Gimlé runs.`,
        },
        { property: "og:title", content: title },
        {
          property: "og:description",
          content: `Outcome history, duration trend and failure signatures for ${testId}.`,
        },
      ],
    };
  },
  component: TestDetailScreen,
});

function TestDetailScreen() {
  const { testId: rawId } = Route.useParams();
  const testId = fromTestParam(rawId);
  const { history, loading, error, load } = useTestHistoryStore();

  useEffect(() => {
    void load(testId);
  }, [testId, load]);

  const points = history?.points ?? [];
  const maxDuration = Math.max(1, ...points.map((p) => p.durationMillis));
  const failedRuns = points.filter((p) => p.outcome !== "PASSED");

  return (
    <div>
      <PageHeader
        eyebrow={history?.module ?? "test"}
        title={<span className="font-mono text-base">{testId.split(":")[1] ?? testId}</span>}
        right={
          <div className="flex items-end gap-6">
            <div className="text-right">
              <HudLabel>score</HudLabel>
              <div className="num text-lg font-semibold text-bad">
                {history?.score.toFixed(1) ?? "—"}
              </div>
            </div>
            <div className="text-right">
              <HudLabel>flake rate</HudLabel>
              <div className="num text-lg font-semibold text-warn">
                {history ? `${(history.flakeRate * 100).toFixed(1)}%` : "—"}
              </div>
            </div>
            {history?.quarantined ? <Chip tone="muted">QUARANTINED</Chip> : null}
          </div>
        }
      >
        <div className="num mt-2 text-[11px] text-muted-foreground">{testId}</div>
      </PageHeader>

      <div className="px-6 py-4">
        {error ? <ErrorBanner message={error} /> : null}
        {loading ? <LoadingBar label="reading test history" /> : null}

        {history ? (
          <div className="grid grid-cols-[1.4fr_1fr] gap-4">
            <div className="space-y-4">
              <HudPanel className="px-3 py-3">
                <HudLabel>outcome history · last {points.length} runs</HudLabel>
                <div className="mt-2">
                  <OutcomeStrip history={points.map((p) => p.outcome)} size={14} gap="gap-[3px]" />
                </div>
              </HudPanel>

              <div className="rounded-md border border-border bg-card">
                <SectionHeader label="duration trend" />
                <div className="px-3 py-3">
                  <div className="relative h-40">
                    {[0, 0.25, 0.5, 0.75, 1].map((g) => (
                      <div
                        key={g}
                        style={{ bottom: `${g * 100}%` }}
                        className="absolute inset-x-0 border-t border-border/40"
                      />
                    ))}
                    <div className="absolute inset-0 flex items-end gap-[3px]">
                      {points.map((p, i) => (
                        <div
                          key={`${p.runId}-${i}`}
                          title={`${p.runId} · ${formatDuration(p.durationMillis)}`}
                          style={{ height: `${(p.durationMillis / maxDuration) * 100}%` }}
                          className={`flex-1 rounded-t-[1px] ${
                            p.outcome === "PASSED"
                              ? "bg-ok/60"
                              : p.outcome === "FAILED"
                                ? "bg-bad"
                                : "bg-warn"
                          }`}
                        />
                      ))}
                    </div>
                  </div>
                  <div className="mt-1 flex justify-between">
                    <span className="num text-[10px] text-muted-foreground">0ms</span>
                    <span className="num text-[10px] text-muted-foreground">
                      max {formatDuration(maxDuration)}
                    </span>
                  </div>
                </div>
              </div>

              <div className="rounded-md border border-border bg-card">
                <SectionHeader
                  label="failing runs"
                  right={
                    <Chip tone={failedRuns.length ? "bad" : "muted"}>{failedRuns.length}</Chip>
                  }
                />
                <table className="w-full border-collapse text-left">
                  <thead>
                    <tr className="border-b border-border bg-muted/30">
                      {["run", "branch", "outcome", "attempts", "duration", "started"].map((h) => (
                        <th key={h} className="px-3 py-1.5">
                          <HudLabel>{h}</HudLabel>
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {failedRuns.map((p, i) => (
                      <tr
                        key={`${p.runId}-${i}`}
                        className="border-b border-border/60 last:border-0"
                      >
                        <td className="px-3 py-1.5">
                          <Link
                            to="/runs/$runId"
                            params={{ runId: p.runId }}
                            className="num text-xs text-primary hover:underline"
                          >
                            {p.runId}
                          </Link>
                        </td>
                        <td className="num px-3 py-1.5 text-xs text-muted-foreground">
                          {p.branch}
                        </td>
                        <td className="px-3 py-1.5">
                          <Chip tone={p.outcome === "FAILED" ? "bad" : "warn"}>{p.outcome}</Chip>
                        </td>
                        <td className="num px-3 py-1.5 text-xs text-muted-foreground">
                          {p.attempts}
                        </td>
                        <td className="num px-3 py-1.5 text-xs text-foreground">
                          {formatDuration(p.durationMillis)}
                        </td>
                        <td className="num px-3 py-1.5 text-[11px] text-muted-foreground">
                          {formatTimestamp(p.startedAt)}
                        </td>
                      </tr>
                    ))}
                    {!failedRuns.length ? (
                      <tr>
                        <td colSpan={6} className="px-3 py-6 text-center">
                          <HudLabel>no failures in window</HudLabel>
                        </td>
                      </tr>
                    ) : null}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="rounded-md border border-border bg-card">
              <SectionHeader label="failure signatures" />
              <ul className="divide-y divide-border/60">
                {history.signatures.map((sig) => (
                  <li key={sig.hash} className="px-3 py-2">
                    <div className="flex items-center justify-between gap-2">
                      <span className="num text-[11px] text-warn">{sig.hash}</span>
                      <Chip tone="bad">{sig.count}×</Chip>
                    </div>
                    <div className="num mt-1 text-xs text-foreground">{sig.exceptionType}</div>
                    <p className="num mt-1 text-[11px] leading-relaxed break-words text-muted-foreground">
                      {sig.message}
                    </p>
                  </li>
                ))}
                {!history.signatures.length ? (
                  <li className="px-3 py-6 text-center">
                    <HudLabel>no signatures recorded</HudLabel>
                  </li>
                ) : null}
              </ul>
            </div>
          </div>
        ) : null}
      </div>
    </div>
  );
}
