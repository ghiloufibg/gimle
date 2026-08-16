import { createFileRoute, Link } from "@tanstack/react-router";
import { ChevronDown, ChevronRight } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { PageHeader } from "@/components/saga/AppShell";
import { ChaosLedger, GherkinTree, SurtrTable } from "@/components/saga/RunAttachments";
import { RunFeed, type FeedRow } from "@/components/saga/RunFeed";
import {
  Chip,
  ErrorBanner,
  HudLabel,
  HudPanel,
  LoadingBar,
  Metric,
  SectionHeader,
  StatusPill,
  TestIdLabel,
} from "@/components/saga/primitives";
import { formatTimestamp, runDuration, shortSha } from "@/lib/format";
import { toTestParam } from "@/lib/testId";
import { useRunDetailStore } from "@/stores/runDetailStore";

export const Route = createFileRoute("/runs/$runId")({
  head: ({ params }) => ({
    meta: [
      { title: `${params.runId} — run detail · Saga` },
      {
        name: "description",
        content: `Live test feed, module progress, grouped failures and holmgang attachments for Gimlé run ${params.runId}.`,
      },
      { property: "og:title", content: `${params.runId} — run detail · Saga` },
      {
        property: "og:description",
        content: `Streaming test results, module progress and grouped failures for run ${params.runId}.`,
      },
    ],
  }),
  component: RunDetailScreen,
});

type TabKey = "gherkin" | "chaos" | "surtr";

function RunDetailScreen() {
  const { runId } = Route.useParams();
  const { detail, events, loading, error, autoFollow, following, load, setAutoFollow, reset } =
    useRunDetailStore();
  const [tab, setTab] = useState<TabKey | null>(null);
  const [openSig, setOpenSig] = useState<string | null>(null);

  useEffect(() => {
    void load(runId);
    return () => reset();
  }, [runId, load, reset]);

  const rows: FeedRow[] = useMemo(() => {
    const failedOnce = new Set(events.filter((e) => e.outcome === "FAILED").map((e) => e.testId));
    return events
      .map((event, i) => ({
        event,
        key: `${event.testId}#${event.attempt}#${i}`,
        flakyRecovery:
          event.outcome === "PASSED" && event.attempt > 1 && failedOnce.has(event.testId),
      }))
      .reverse();
  }, [events]);

  const failureGroups = useMemo(() => {
    const groups = new Map<
      string,
      { signature: string; type: string; message: string; events: typeof events }
    >();
    events
      .filter((e) => e.outcome === "FAILED")
      .forEach((e) => {
        const key = e.failureSignature ?? "unknown";
        const existing = groups.get(key);
        if (existing) existing.events.push(e);
        else
          groups.set(key, {
            signature: key,
            type: e.failureType ?? "java.lang.AssertionError",
            message: e.failureMessage ?? "",
            events: [e],
          });
      });
    return [...groups.values()].sort((a, b) => b.events.length - a.events.length);
  }, [events]);

  const attachments = detail?.attachments;
  const tabs: Array<{ key: TabKey; label: string }> = [
    ...(attachments?.gherkin?.length ? [{ key: "gherkin" as const, label: "Gherkin" }] : []),
    ...(attachments?.chaosLedger?.length ? [{ key: "chaos" as const, label: "Chaos" }] : []),
    ...(attachments?.surtr?.length ? [{ key: "surtr" as const, label: "Surtr" }] : []),
  ];
  const activeTab = tab ?? tabs[0]?.key ?? null;

  const run = detail?.run;

  return (
    <div>
      <PageHeader
        eyebrow="run detail"
        title={<span className="font-mono">{runId}</span>}
        right={
          run ? (
            <div className="flex items-end gap-6">
              <StatusPill status={run.status} />
              <Metric label="tests" value={run.totals.tests} />
              <Metric label="passed" value={run.totals.passed} tone="ok" />
              <Metric label="failed" value={run.totals.failed} tone="bad" />
              <Metric label="flaky" value={run.totals.flaky} tone="warn" />
            </div>
          ) : null
        }
      >
        {run ? (
          <div className="mt-3 flex flex-wrap gap-x-8 gap-y-2">
            <Meta label="branch" value={`${run.branch} @ ${shortSha(run.gitSha)}`} />
            <Meta label="command" value={run.command} />
            <Meta label="started" value={formatTimestamp(run.startedAt)} />
            <Meta label="duration" value={runDuration(run.startedAt, run.finishedAt)} />
            <Meta label="modules" value={String(run.modules.length)} />
          </div>
        ) : null}
      </PageHeader>

      <div className="px-6 py-4">
        {error ? <ErrorBanner message={error} /> : null}
        {loading ? <LoadingBar label="loading run" /> : null}

        {detail ? (
          <>
            <div className="grid grid-cols-5 gap-3">
              {detail.moduleProgress.map((m) => {
                const ratio = Math.min(1, m.completed / Math.max(1, m.total));
                return (
                  <HudPanel key={m.module} className="px-3 py-2">
                    <div className="flex items-center justify-between">
                      <HudLabel>{m.module.replace("gimle-", "")}</HudLabel>
                      <span className="num text-[11px] text-muted-foreground">
                        {m.completed}/{m.total}
                      </span>
                    </div>
                    <div className="mt-2 h-1.5 overflow-hidden rounded-sm bg-muted">
                      <div
                        style={{ width: `${ratio * 100}%` }}
                        className={`h-full ${m.failed ? "bg-warn" : "bg-signal"}`}
                      />
                    </div>
                    <div className="num mt-1 text-[10px] text-muted-foreground">
                      {m.failed ? `${m.failed} failed` : "clean"}
                    </div>
                  </HudPanel>
                );
              })}
            </div>

            <div className="mt-4 grid grid-cols-[1.4fr_1fr] gap-4">
              <div className="overflow-hidden rounded-md border border-border bg-card">
                <SectionHeader
                  label={`feed · ${rows.length} events`}
                  right={
                    <button
                      type="button"
                      onClick={() => setAutoFollow(!autoFollow)}
                      className={`num rounded-sm border px-2 py-0.5 text-[10px] tracking-[0.12em] uppercase transition-colors ${
                        following
                          ? "border-signal/40 bg-info-bg text-signal"
                          : "border-border bg-background text-muted-foreground hover:bg-accent"
                      }`}
                    >
                      {following ? "following" : "auto-follow off"}
                    </button>
                  }
                />
                <RunFeed rows={rows} />
              </div>

              <div className="overflow-hidden rounded-md border border-border bg-card">
                <SectionHeader
                  label="failures by signature"
                  right={
                    <Chip tone={failureGroups.length ? "bad" : "muted"}>
                      {failureGroups.length}
                    </Chip>
                  }
                />
                <div className="max-h-[28rem] divide-y divide-border/60 overflow-y-auto">
                  {failureGroups.map((group) => {
                    const open = openSig === group.signature;
                    return (
                      <div key={group.signature}>
                        <button
                          type="button"
                          onClick={() => setOpenSig(open ? null : group.signature)}
                          className="flex w-full items-start gap-2 px-3 py-2 text-left transition-colors hover:bg-accent/40"
                        >
                          {open ? (
                            <ChevronDown className="mt-0.5 h-3 w-3 shrink-0 text-muted-foreground" />
                          ) : (
                            <ChevronRight className="mt-0.5 h-3 w-3 shrink-0 text-muted-foreground" />
                          )}
                          <div className="min-w-0 flex-1">
                            <div className="flex items-center gap-2">
                              <span className="num text-[11px] text-warn">{group.signature}</span>
                              <Chip tone="bad">{group.events.length}×</Chip>
                            </div>
                            <div className="num mt-0.5 text-[11px] text-foreground">
                              {group.type}
                            </div>
                            <p className="num mt-0.5 line-clamp-2 text-[10px] text-muted-foreground">
                              {group.message}
                            </p>
                          </div>
                        </button>
                        {open ? (
                          <div className="border-t border-border/60 bg-muted/30 px-3 py-2">
                            <HudLabel>affected tests</HudLabel>
                            <ul className="mt-1 mb-2 space-y-0.5">
                              {group.events.map((e, i) => (
                                <li key={`${e.testId}-${i}`} className="truncate">
                                  <Link
                                    to="/tests/$testId"
                                    params={{ testId: toTestParam(e.testId) }}
                                    className="hover:underline"
                                  >
                                    <TestIdLabel testId={e.testId} />
                                  </Link>
                                </li>
                              ))}
                            </ul>
                            <HudLabel>failure message</HudLabel>
                            <pre className="mt-1 max-h-56 overflow-auto rounded-sm border border-border bg-background px-2 py-2 font-mono text-[10px] leading-relaxed text-muted-foreground">
                              {group.message || "no failure message recorded"}
                            </pre>
                          </div>
                        ) : null}
                      </div>
                    );
                  })}
                  {!failureGroups.length ? (
                    <div className="px-3 py-8 text-center">
                      <HudLabel>no failures recorded</HudLabel>
                    </div>
                  ) : null}
                </div>
              </div>
            </div>

            {tabs.length ? (
              <div className="mt-4 overflow-hidden rounded-md border border-border bg-card">
                <div className="flex border-b border-border bg-muted/30">
                  {tabs.map((t) => (
                    <button
                      key={t.key}
                      type="button"
                      onClick={() => setTab(t.key)}
                      className={`num border-r border-border px-3 py-2 text-[11px] tracking-[0.12em] uppercase transition-colors ${
                        activeTab === t.key
                          ? "bg-card text-foreground"
                          : "text-muted-foreground hover:bg-accent/40"
                      }`}
                    >
                      {t.label}
                    </button>
                  ))}
                </div>
                {activeTab === "gherkin" && attachments?.gherkin ? (
                  <GherkinTree features={attachments.gherkin} />
                ) : null}
                {activeTab === "chaos" && attachments?.chaosLedger ? (
                  <ChaosLedger strikes={attachments.chaosLedger} />
                ) : null}
                {activeTab === "surtr" && attachments?.surtr ? (
                  <SurtrTable phases={attachments.surtr} />
                ) : null}
              </div>
            ) : null}
          </>
        ) : null}
      </div>
    </div>
  );
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <HudLabel>{label}</HudLabel>
      <div className="num mt-0.5 truncate text-xs text-foreground">{value}</div>
    </div>
  );
}
