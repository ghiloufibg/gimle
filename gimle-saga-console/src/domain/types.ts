export type RunStatus = "live" | "passed" | "failed" | "abandoned";
export type Outcome = "PASSED" | "FAILED" | "ABORTED" | "SKIPPED";

export interface RunTotals {
  tests: number;
  passed: number;
  failed: number;
  skipped: number;
  flaky: number;
}

export interface Run {
  runId: string;
  startedAt: string;
  finishedAt?: string;
  gitSha: string;
  branch: string;
  command: string;
  status: RunStatus;
  totals: RunTotals;
  modules: string[];
}

export interface TestResultEvent {
  runId: string;
  testId: string;
  attempt: number;
  outcome: Outcome;
  durationMillis: number;
  failureType?: string;
  failureMessage?: string;
  failureSignature?: string;
  finishedAt: string;
}

export interface FailureSignature {
  hash: string;
  exceptionType: string;
  message: string;
  count: number;
}

export interface FlakyEntry {
  testId: string;
  module: string;
  score: number;
  flakeRate: number;
  occurrences: number;
  runsSeen: number;
  firstSeen: string;
  lastSeen: string;
  quarantined: boolean;
  signatures: FailureSignature[];
  history: Outcome[];
}

export interface GherkinStep {
  keyword: string;
  text: string;
  status: Outcome;
  durationMillis: number;
}
export interface GherkinScenario {
  name: string;
  status: Outcome;
  steps: GherkinStep[];
}
export interface GherkinFeature {
  name: string;
  module: string;
  scenarios: GherkinScenario[];
}

export type StrikeKind = "worker-kill" | "leader-bounce" | "link-cut";
export interface ChaosStrike {
  atMillis: number;
  kind: StrikeKind;
  target: string;
  recoveryMillis: number;
  recovered: boolean;
}

export interface SurtrPhase {
  phase: string;
  durationMillis: number;
  p50Millis: number;
  p99Millis: number;
  failures: number;
  throughput: number;
}

export interface RunAttachments {
  gherkin?: GherkinFeature[];
  chaosLedger?: ChaosStrike[];
  surtr?: SurtrPhase[];
}

export interface ModuleProgress {
  module: string;
  total: number;
  completed: number;
  failed: number;
}

export interface RunDetail {
  run: Run;
  moduleProgress: ModuleProgress[];
  attachments: RunAttachments;
}

export interface TestHistoryPoint {
  runId: string;
  startedAt: string;
  branch: string;
  outcome: Outcome;
  attempts: number;
  durationMillis: number;
}

export interface TestHistory {
  testId: string;
  module: string;
  quarantined: boolean;
  flakeRate: number;
  score: number;
  points: TestHistoryPoint[];
  signatures: FailureSignature[];
}

export type FlakyWindow = 7 | 30 | 90;

export interface FlakyFilter {
  module: string | "all";
  window: FlakyWindow;
  quarantinedOnly: boolean;
}

export interface FlakySummary {
  activeFlaky: number;
  budgetUsedPct: number;
  budgetAllowance: number;
  budgetSpent: number;
  worstOffender: { testId: string; score: number } | null;
}

export interface FlakyBoard {
  entries: FlakyEntry[];
  summary: FlakySummary;
  modules: string[];
}

export interface DurationRegression {
  testId: string;
  baseMillis: number;
  headMillis: number;
  deltaPct: number;
}

export interface CompareResult {
  base: Run;
  head: Run;
  newlyFailing: string[];
  newlyPassing: string[];
  newlyFlaky: string[];
  regressions: DurationRegression[];
}
