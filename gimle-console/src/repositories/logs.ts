import type {
  CrashDump,
  LogCategory,
  LogLevel,
  LogLine,
  LogTarget,
  Page,
  ProcessRole,
  StructuredLogLine,
} from "@/types";
import { deployments, nodes } from "./fixture";
import { delay } from "./util";

const LOGGERS = [
  "com.example.checkout.OrderHandler",
  "com.example.billing.PaymentGateway",
  "org.gimle.platform.ModuleLifecycle",
  "org.gimle.platform.Scheduler",
  "org.gimle.platform.HeartbeatWorker",
  "org.gimle.node.NodeSupervisor",
  "org.gimle.controlplane.QuotaEnforcer",
];
const MSGS_INFO = [
  "order accepted",
  "handled request in 42ms",
  "module transitioned to ACTIVE",
  "scheduled instance on {node}",
  "heartbeat received from {node}",
  "request forwarded to worker",
  "quota check passed",
  "artifact resolved from cache",
];
const MSGS_WARN = [
  "queue depth approaching threshold",
  "instance not ready after 30s",
  "backpressure engaged on downstream",
  "heartbeat delayed by 4s",
];
const MSGS_ERROR = [
  "failed to bind instance to node",
  "quota violation: memory exceeded",
  "downstream call timed out",
  "unhandled exception in handler",
];
const RAW_LINES = [
  "systemd[1]: Started gimle-node.service.",
  "kernel: [ 1234.567] eth0: link up, 10Gbps",
  "sshd[8123]: Accepted publickey for ops",
  "dmesg: cgroup limit reached for slice gimle.slice",
];

function pick<T>(a: T[]): T {
  return a[Math.floor(Math.random() * a.length)];
}
function intBetween(lo: number, hi: number) {
  return Math.floor(Math.random() * (hi - lo + 1)) + lo;
}

function weightedLevel(): LogLevel {
  const r = Math.random();
  if (r < 0.02) return "ERROR";
  if (r < 0.08) return "WARN";
  if (r < 0.75) return "INFO";
  if (r < 0.9) return "DEBUG";
  return "TRACE";
}

function makeStructured(target: LogTarget, ts: Date): StructuredLogLine {
  const level = weightedLevel();
  const category: LogCategory =
    target.kind === "instance"
      ? target.category
      : target.category === "SYSTEM"
        ? "SYSTEM"
        : "PLATFORM";
  const processRole: ProcessRole =
    target.kind === "instance" ? "WORKER" : target.kind === "node" ? "NODE" : "CONTROLLER";
  const nodeId =
    target.kind === "node"
      ? target.nodeId
      : target.kind === "instance"
        ? (deployments
            .find((d) => d.spec.name === target.deploymentName)
            ?.instances.find((i) => i.instanceIndex === target.instanceIndex)?.nodeId ??
          pick(nodes).nodeId)
        : pick(nodes).nodeId;
  const logger = pick(LOGGERS);
  const thread =
    target.kind === "instance"
      ? `${target.deploymentName}-worker-${intBetween(1, 8)}`
      : target.kind === "node"
        ? `node-supervisor-${intBetween(1, 4)}`
        : `controlplane-${intBetween(1, 4)}`;
  const msgPool = level === "ERROR" ? MSGS_ERROR : level === "WARN" ? MSGS_WARN : MSGS_INFO;
  const message = pick(msgPool).replace("{node}", nodeId);

  const base: StructuredLogLine = {
    timestamp: ts.toISOString(),
    level,
    logger,
    thread,
    message,
    category,
    processRole,
    nodeId,
  };

  if (target.kind === "instance") {
    const d = deployments.find((x) => x.spec.name === target.deploymentName);
    if (d) {
      base.deploymentName = d.spec.name;
      base.moduleId = d.spec.moduleId.name;
      base.moduleVersion = d.spec.moduleId.version;
      base.instanceIndex = target.instanceIndex;
      if (d.spec.tenantId) base.tenantId = d.spec.tenantId;
    }
  }
  return base;
}

function makeLine(target: LogTarget, ts: Date): LogLine {
  const isSystem =
    (target.kind === "node" || target.kind === "controlplane") && target.category === "SYSTEM";
  if (isSystem && Math.random() < 0.35) {
    return { timestamp: ts.toISOString(), category: "SYSTEM", raw: pick(RAW_LINES) };
  }
  return makeStructured(target, ts);
}

export interface LogsRepository {
  fetchPage(args: {
    target: LogTarget;
    cursor: string | null;
    pageSize: number;
  }): Promise<Page<LogLine>>;
  openFollow(target: LogTarget, onLine: (line: LogLine) => void): () => void;
  /** Only instance targets have a worker JVM to crash; node/controlplane targets always resolve
   * to an empty list (no backend route exists for those). */
  listCrashDumps(target: LogTarget): Promise<CrashDump[]>;
}

export class MockLogsRepository implements LogsRepository {
  async fetchPage({
    target,
    cursor,
    pageSize,
  }: {
    target: LogTarget;
    cursor: string | null;
    pageSize: number;
  }) {
    // cursor = number of ms in the past to end at (older pages).
    const endAgoMs = cursor ? parseInt(cursor, 10) : 0;
    const end = Date.now() - endAgoMs;
    const items: LogLine[] = [];
    for (let i = pageSize - 1; i >= 0; i--) {
      const ts = new Date(end - i * intBetween(500, 3000));
      items.push(makeLine(target, ts));
    }
    const nextCursor = String(endAgoMs + pageSize * 1500);
    return delay({ items, nextCursor });
  }

  openFollow(target: LogTarget, onLine: (line: LogLine) => void): () => void {
    let stopped = false;
    const tick = () => {
      if (stopped) return;
      onLine(makeLine(target, new Date()));
      const next = 800 + Math.random() * 1200;
      setTimeout(tick, next);
    };
    const initial = setTimeout(tick, 400 + Math.random() * 400);
    return () => {
      stopped = true;
      clearTimeout(initial);
    };
  }

  // Crash dumps are a rare event -- not worth faking; the mock always reports none.
  async listCrashDumps(): Promise<CrashDump[]> {
    return delay([]);
  }
}
