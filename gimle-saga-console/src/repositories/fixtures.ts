import type {
  ChaosStrike,
  FailureSignature,
  FlakyEntry,
  GherkinFeature,
  Outcome,
  Run,
  RunAttachments,
  SurtrPhase,
  TestResultEvent,
} from "@/domain/types";
import { moduleOf } from "@/lib/testId";

export const MODULES = [
  "gimle-mimir",
  "gimle-fabric",
  "gimle-agent",
  "gimle-smoke-tests",
  "gimle-controlplane",
] as const;

const TEST_NAMES: Record<string, string[]> = {
  "gimle-mimir": [
    "RaftClusterTest#leader_election_works",
    "RaftClusterTest#a_client_keeps_writing_successfully_across_a_forced_leader_failover",
    "RaftClusterTest#a_follower_catches_up_after_a_long_network_partition",
    "LogCompactionTest#snapshot_install_truncates_the_replicated_log",
    "LogCompactionTest#a_restarted_node_replays_only_entries_after_the_snapshot",
    "QuorumWriteTest#a_write_is_rejected_when_quorum_is_unreachable",
  ],
  "gimle-fabric": [
    "GossipMembershipTest#a_departed_member_is_reaped_from_every_view",
    "GossipMembershipTest#membership_converges_within_three_gossip_rounds",
    "LinkFailureTest#a_severed_link_is_routed_around_without_dropping_frames",
    "BackpressureTest#a_slow_consumer_does_not_stall_the_whole_fabric",
    "FrameCodecTest#oversized_frames_are_rejected_before_allocation",
  ],
  "gimle-agent": [
    "AgentLeaseTest#an_expired_lease_is_renewed_before_the_workload_is_evicted",
    "AgentLeaseTest#a_crashed_agent_releases_its_lease_within_the_grace_period",
    "QuotaEnforcementTest#a_tenant_over_its_cpu_quota_is_throttled_not_killed",
    "SidecarBootTest#the_sidecar_reports_ready_only_after_the_jvm_warms_up",
  ],
  "gimle-smoke-tests": [
    "HolmgangSmokeTest#a_fresh_cluster_serves_traffic_within_thirty_seconds",
    "HolmgangSmokeTest#rolling_restart_keeps_error_rate_under_one_percent",
    "EndpointReachabilityTest#every_advertised_endpoint_answers_health_checks",
  ],
  "gimle-controlplane": [
    "SchedulerPlacementTest#a_pending_workload_lands_on_the_least_loaded_node",
    "SchedulerPlacementTest#anti_affinity_rules_survive_a_zone_outage",
    "ApiVersioningTest#a_deprecated_field_is_still_accepted_for_one_release",
    "ReconcilerTest#a_drifted_workload_is_reconciled_back_to_the_desired_spec",
    "AdmissionWebhookTest#an_invalid_spec_is_rejected_with_a_field_path",
  ],
};

export const ALL_TEST_IDS: string[] = Object.entries(TEST_NAMES).flatMap(([module, names]) =>
  names.map((n) => `${module}:${n}`),
);

export const methodOf = (testId: string) => testId.split(":")[1] ?? testId;

const FAILURES: Array<{ type: string; message: string; hash: string }> = [
  {
    type: "java.util.concurrent.TimeoutException",
    hash: "9f2ac1d4",
    message:
      "no leader elected within PT10S; term=47 votes={node-1=candidate, node-2=follower, node-3=unreachable}",
  },
  {
    type: "io.gimle.raft.NotLeaderException",
    hash: "1b77e0aa",
    message:
      "write rejected: node-2 is follower, current leader hint=node-3 (term 51), commitIndex=118432",
  },
  {
    type: "io.gimle.fabric.GossipConvergenceException",
    hash: "c40e93b2",
    message:
      "membership view diverged after 5 rounds: node-4 seen as ALIVE by 2/5 peers, SUSPECT by 3/5",
  },
  {
    type: "io.gimle.agent.QuotaExceededException",
    hash: "77de5510",
    message: "tenant=acme-prod cpu quota 4000m exceeded (observed 4820m) for 3 consecutive windows",
  },
  {
    type: "org.opentest4j.AssertionFailedError",
    hash: "3aa8fb17",
    message: "expected error rate <= 0.01 but was 0.0341 during rolling restart of 3 nodes",
  },
  {
    type: "io.gimle.fabric.LinkResetException",
    hash: "50bb2c9e",
    message: "link node-1<->node-5 reset by peer after 12 frames; 3 frames unacknowledged",
  },
];

const BRANCHES = [
  "main",
  "main",
  "main",
  "feat/raft-prevote",
  "fix/gossip-suspect-timeout",
  "release/1.24",
  "feat/quota-windows",
];

const COMMANDS = [
  "./gradlew :gimle-mimir:test --tests '*Raft*'",
  "./gradlew check -Pholmgang",
  "./gradlew holmgang --scenario=chaos-baseline",
  "./gradlew test integrationTest",
  "./gradlew :gimle-controlplane:test --parallel",
];

/** Deterministic PRNG so fixtures are stable across reloads. */
function mulberry(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const rnd = mulberry(20260816);
const pick = <T>(arr: readonly T[]) => arr[Math.floor(rnd() * arr.length)]!;
const int = (min: number, max: number) => min + Math.floor(rnd() * (max - min + 1));

const sha = () => Array.from({ length: 7 }, () => "0123456789abcdef"[int(0, 15)]!).join("");

const NOW = Date.parse("2026-08-16T10:20:00Z");

export interface MockRun extends Run {
  events: TestResultEvent[];
  attachments: RunAttachments;
}

function stackTrace(type: string, message: string, testId: string): string {
  const cls = methodOf(testId).split("#")[0];
  return [
    `${type}: ${message}`,
    `\tat io.gimle.${moduleOf(testId).replace("gimle-", "")}.${cls}.await(${cls}.java:${int(60, 400)})`,
    `\tat io.gimle.testkit.Holmgang.awaitQuorum(Holmgang.java:${int(100, 260)})`,
    `\tat io.gimle.testkit.ClusterHarness.step(ClusterHarness.java:${int(40, 190)})`,
    `\tat ${cls}.${methodOf(testId).split("#")[1]}(${cls}.java:${int(20, 120)})`,
    `\tat java.base/java.lang.reflect.Method.invoke(Method.java:580)`,
    `\tat org.junit.platform.engine.support.hierarchical.NodeTestTask.execute(NodeTestTask.java:141)`,
  ].join("\n");
}

function buildEvents(runId: string, modules: string[], failRatio: number, upTo = 1) {
  const events: TestResultEvent[] = [];
  const ids = ALL_TEST_IDS.filter((t) => modules.includes(moduleOf(t)));
  const count = Math.max(1, Math.floor(ids.length * upTo));
  const start = NOW - int(30, 900) * 60_000;
  ids.slice(0, count).forEach((testId, i) => {
    const roll = rnd();
    const duration = int(45, 9800);
    const finishedAt = new Date(start + i * int(1200, 5400)).toISOString();
    if (roll < failRatio) {
      const f = pick(FAILURES);
      const flakyRecovery = rnd() < 0.45;
      events.push({
        runId,
        testId,
        attempt: 1,
        outcome: "FAILED",
        durationMillis: duration,
        failureType: f.type,
        failureMessage: f.message,
        failureSignature: f.hash,
        finishedAt,
      });
      if (flakyRecovery) {
        events.push({
          runId,
          testId,
          attempt: 2,
          outcome: "PASSED",
          durationMillis: int(45, 4000),
          finishedAt: new Date(start + i * int(1200, 5400) + 4200).toISOString(),
        });
      }
    } else if (roll > 0.97) {
      events.push({
        runId,
        testId,
        attempt: 1,
        outcome: "SKIPPED",
        durationMillis: 0,
        finishedAt,
      });
    } else {
      events.push({
        runId,
        testId,
        attempt: 1,
        outcome: "PASSED",
        durationMillis: duration,
        finishedAt,
      });
    }
  });
  return events;
}

function totalsFrom(events: TestResultEvent[]) {
  const byTest = new Map<string, TestResultEvent[]>();
  events.forEach((e) => byTest.set(e.testId, [...(byTest.get(e.testId) ?? []), e]));
  let passed = 0;
  let failed = 0;
  let skipped = 0;
  let flaky = 0;
  byTest.forEach((attempts) => {
    const last = attempts[attempts.length - 1]!;
    if (last.outcome === "SKIPPED") skipped++;
    else if (last.outcome === "PASSED") {
      passed++;
      if (attempts.some((a) => a.outcome === "FAILED")) flaky++;
    } else failed++;
  });
  return { tests: byTest.size, passed, failed, skipped, flaky };
}

const GHERKIN: GherkinFeature[] = [
  {
    name: "Forced leader failover",
    module: "gimle-mimir",
    scenarios: [
      {
        name: "A client keeps writing across a leader bounce",
        status: "PASSED",
        steps: [
          {
            keyword: "Given",
            text: "a 5 node mimir cluster with quorum 3",
            status: "PASSED",
            durationMillis: 4120,
          },
          {
            keyword: "And",
            text: "a writer issuing 200 writes/s",
            status: "PASSED",
            durationMillis: 890,
          },
          {
            keyword: "When",
            text: "the leader is killed with SIGKILL",
            status: "PASSED",
            durationMillis: 210,
          },
          {
            keyword: "Then",
            text: "a new leader is elected within 5s",
            status: "PASSED",
            durationMillis: 3380,
          },
          {
            keyword: "And",
            text: "no acknowledged write is lost",
            status: "PASSED",
            durationMillis: 1240,
          },
        ],
      },
      {
        name: "A follower catches up after a partition heals",
        status: "FAILED",
        steps: [
          {
            keyword: "Given",
            text: "node-4 is partitioned from the quorum",
            status: "PASSED",
            durationMillis: 1550,
          },
          {
            keyword: "When",
            text: "the partition is healed after 45s",
            status: "PASSED",
            durationMillis: 45120,
          },
          {
            keyword: "Then",
            text: "node-4 reaches the commit index within 10s",
            status: "FAILED",
            durationMillis: 10004,
          },
          {
            keyword: "And",
            text: "no snapshot transfer is required",
            status: "SKIPPED",
            durationMillis: 0,
          },
        ],
      },
    ],
  },
  {
    name: "Tenant quota enforcement",
    module: "gimle-agent",
    scenarios: [
      {
        name: "A tenant over its cpu quota is throttled",
        status: "PASSED",
        steps: [
          {
            keyword: "Given",
            text: "tenant acme-prod with a 4000m cpu quota",
            status: "PASSED",
            durationMillis: 640,
          },
          {
            keyword: "When",
            text: "the workload bursts to 6000m for 30s",
            status: "PASSED",
            durationMillis: 30210,
          },
          {
            keyword: "Then",
            text: "the agent throttles instead of evicting",
            status: "PASSED",
            durationMillis: 1180,
          },
        ],
      },
    ],
  },
];

const CHAOS: ChaosStrike[] = [
  {
    atMillis: 12_000,
    kind: "worker-kill",
    target: "gimle-agent/node-3",
    recoveryMillis: 4_320,
    recovered: true,
  },
  {
    atMillis: 48_500,
    kind: "leader-bounce",
    target: "gimle-mimir/node-1",
    recoveryMillis: 3_180,
    recovered: true,
  },
  {
    atMillis: 91_000,
    kind: "link-cut",
    target: "node-1 <-> node-5",
    recoveryMillis: 11_940,
    recovered: true,
  },
  {
    atMillis: 152_000,
    kind: "worker-kill",
    target: "gimle-fabric/node-2",
    recoveryMillis: 2_640,
    recovered: true,
  },
  {
    atMillis: 204_000,
    kind: "leader-bounce",
    target: "gimle-mimir/node-4",
    recoveryMillis: 18_400,
    recovered: false,
  },
  {
    atMillis: 265_000,
    kind: "link-cut",
    target: "node-2 <-> node-3",
    recoveryMillis: 6_710,
    recovered: true,
  },
];

const SURTR: SurtrPhase[] = [
  {
    phase: "warmup",
    durationMillis: 61_000,
    p50Millis: 12,
    p99Millis: 84,
    failures: 0,
    throughput: 1420,
  },
  {
    phase: "ramp",
    durationMillis: 122_500,
    p50Millis: 18,
    p99Millis: 141,
    failures: 3,
    throughput: 3880,
  },
  {
    phase: "steady",
    durationMillis: 300_000,
    p50Millis: 21,
    p99Millis: 168,
    failures: 11,
    throughput: 4210,
  },
  {
    phase: "chaos",
    durationMillis: 180_000,
    p50Millis: 34,
    p99Millis: 612,
    failures: 147,
    throughput: 3140,
  },
  {
    phase: "drain",
    durationMillis: 45_000,
    p50Millis: 16,
    p99Millis: 98,
    failures: 0,
    throughput: 980,
  },
];

function buildRuns(): MockRun[] {
  const runs: MockRun[] = [];
  for (let i = 0; i < 24; i++) {
    const runId = `run-2026${String(int(10, 99))}-${(9931 - i * 7).toString()}`;
    const modules = i % 4 === 0 ? [...MODULES] : MODULES.filter(() => rnd() > 0.35).slice(0, 4);
    const mods = modules.length ? modules : ["gimle-mimir"];
    const holmgang = i % 3 === 0;
    const failRatio = i === 0 ? 0.18 : rnd() * 0.22;
    const live = i === 0;
    const events = buildEvents(runId, mods, failRatio, live ? 0.55 : 1);
    const totals = totalsFrom(events);
    const startedAt = new Date(NOW - i * int(38, 190) * 60_000).toISOString();
    const durationMs = int(4, 46) * 60_000;
    runs.push({
      runId,
      startedAt,
      ...(live ? {} : { finishedAt: new Date(Date.parse(startedAt) + durationMs).toISOString() }),
      gitSha: sha() + sha().slice(0, 3),
      branch: pick(BRANCHES),
      command: holmgang ? "./gradlew holmgang --scenario=chaos-baseline" : pick(COMMANDS),
      status: live ? "live" : totals.failed > 0 ? "failed" : "passed",
      totals,
      modules: mods,
      events,
      attachments: holmgang ? { gherkin: GHERKIN, chaosLedger: CHAOS, surtr: SURTR } : {},
    });
  }
  return runs;
}

export const MOCK_RUNS: MockRun[] = buildRuns();

export function pendingTestIdsFor(run: MockRun): string[] {
  const done = new Set(run.events.map((e) => e.testId));
  return ALL_TEST_IDS.filter((t) => run.modules.includes(moduleOf(t)) && !done.has(t));
}

export function makeLiveEvent(runId: string, testId: string): TestResultEvent {
  const roll = rnd();
  if (roll < 0.22) {
    const f = pick(FAILURES);
    return {
      runId,
      testId,
      attempt: 1,
      outcome: "FAILED",
      durationMillis: int(120, 9200),
      failureType: f.type,
      failureMessage: f.message,
      failureSignature: f.hash,
      finishedAt: new Date().toISOString(),
    };
  }
  return {
    runId,
    testId,
    attempt: roll > 0.9 ? 2 : 1,
    outcome: "PASSED",
    durationMillis: int(60, 7400),
    finishedAt: new Date().toISOString(),
  };
}

export function traceFor(event: TestResultEvent): string {
  return stackTrace(
    event.failureType ?? "java.lang.AssertionError",
    event.failureMessage ?? "assertion failed",
    event.testId,
  );
}

function historyStrip(flakeRate: number, len = 30): Outcome[] {
  return Array.from({ length: len }, () => {
    const r = rnd();
    if (r < flakeRate * 0.55) return "FAILED";
    if (r < flakeRate) return "ABORTED";
    return "PASSED";
  });
}

function signaturesFor(count: number): FailureSignature[] {
  const chosen: FailureSignature[] = [];
  for (let i = 0; i < count; i++) {
    const f = FAILURES[(i * 3 + int(0, 5)) % FAILURES.length]!;
    if (chosen.some((c) => c.hash === f.hash)) continue;
    chosen.push({ hash: f.hash, exceptionType: f.type, message: f.message, count: int(2, 41) });
  }
  return chosen.length ? chosen : [{ ...FAILURES[0]!, exceptionType: FAILURES[0]!.type, count: 3 }];
}

export const MOCK_FLAKY: FlakyEntry[] = ALL_TEST_IDS.filter(() => rnd() > 0.28)
  .map((testId) => {
    const flakeRate = Number((rnd() * 0.42 + 0.01).toFixed(3));
    const occurrences = int(2, 63);
    const runsSeen = occurrences + int(8, 140);
    const score = Number((flakeRate * 100 * 1.4 + Math.log2(occurrences + 1) * 6).toFixed(1));
    const lastSeenDays = int(0, 88);
    return {
      testId,
      module: moduleOf(testId),
      score,
      flakeRate,
      occurrences,
      runsSeen,
      firstSeen: new Date(NOW - int(90, 420) * 86_400_000).toISOString(),
      lastSeen: new Date(NOW - lastSeenDays * 86_400_000).toISOString(),
      quarantined: score > 55 && rnd() > 0.45,
      signatures: signaturesFor(int(1, 3)),
      history: historyStrip(flakeRate),
    } satisfies FlakyEntry;
  })
  .sort((a, b) => b.score - a.score);
