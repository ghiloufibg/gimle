import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
import type { Outcome, RunStatus } from "@/domain/types";

export function HudLabel({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn("hud-label", className)}>{children}</div>;
}

export function HudPanel({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn("hud-panel rounded-md", className)}>{children}</div>;
}

export function Metric({
  label,
  value,
  tone = "default",
  sub,
}: {
  label: string;
  value: ReactNode;
  tone?: "default" | "ok" | "warn" | "bad" | "info" | "signal";
  sub?: ReactNode;
}) {
  const toneClass = {
    default: "text-foreground",
    ok: "text-ok",
    warn: "text-warn",
    bad: "text-bad",
    info: "text-info",
    signal: "text-signal",
  }[tone];
  return (
    <div>
      <HudLabel>{label}</HudLabel>
      <div className={cn("num mt-1 text-2xl leading-none font-semibold", toneClass)}>{value}</div>
      {sub ? <div className="num mt-1 text-[11px] text-muted-foreground">{sub}</div> : null}
    </div>
  );
}

export function StatusPill({ status }: { status: RunStatus }) {
  const map = {
    live: "border-signal/40 bg-info-bg text-signal",
    passed: "border-ok/30 bg-ok-bg text-ok",
    failed: "border-bad/30 bg-bad-bg text-bad",
  } as const;
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-sm border px-1.5 py-0.5 font-mono text-[10px] font-bold tracking-[0.14em] uppercase",
        map[status],
      )}
    >
      <span
        className={cn(
          "inline-block h-1.5 w-1.5 rounded-full",
          status === "live" && "signal-dot bg-signal",
          status === "passed" && "bg-ok",
          status === "failed" && "bg-bad",
        )}
      />
      {status}
    </span>
  );
}

export function Chip({
  children,
  tone = "muted",
  className,
}: {
  children: ReactNode;
  tone?: "muted" | "ok" | "warn" | "bad" | "info";
  className?: string;
}) {
  const map = {
    muted: "border-border bg-muted text-muted-foreground",
    ok: "border-ok/30 bg-ok-bg text-ok",
    warn: "border-warn/30 bg-warn-bg text-warn",
    bad: "border-bad/30 bg-bad-bg text-bad",
    info: "border-info/30 bg-info-bg text-info",
  }[tone];
  return (
    <span
      className={cn(
        "num inline-flex items-center gap-1 rounded-sm border px-1.5 py-0.5 text-[10px] font-medium",
        map,
        className,
      )}
    >
      {children}
    </span>
  );
}

export const outcomeTone = (outcome: Outcome) =>
  outcome === "PASSED"
    ? "ok"
    : outcome === "FAILED"
      ? "bad"
      : outcome === "ABORTED"
        ? "warn"
        : "info";

export function OutcomeDot({ outcome, className }: { outcome: Outcome; className?: string }) {
  const tone = outcomeTone(outcome);
  return (
    <span
      className={cn(
        "inline-block h-2 w-2 shrink-0 rounded-full",
        tone === "ok" && "bg-ok",
        tone === "bad" && "bg-bad",
        tone === "warn" && "bg-warn",
        tone === "info" && "bg-muted-foreground",
        className,
      )}
    />
  );
}

export function OutcomeStrip({
  history,
  size = 7,
  gap = "gap-[2px]",
}: {
  history: Outcome[];
  size?: number;
  gap?: string;
}) {
  return (
    <div className={cn("flex items-center", gap)}>
      {history.map((outcome, i) => {
        const tone = outcomeTone(outcome);
        return (
          <span
            key={i}
            title={outcome}
            style={{ width: size, height: size }}
            className={cn(
              "inline-block rounded-[1px]",
              tone === "ok" && "bg-ok/70",
              tone === "bad" && "bg-bad",
              tone === "warn" && "bg-warn",
              tone === "info" && "bg-muted-foreground/40",
            )}
          />
        );
      })}
    </div>
  );
}

export function TestIdLabel({ testId, className }: { testId: string; className?: string }) {
  const [module, method] = [testId.split(":")[0] ?? "", testId.split(":")[1] ?? testId];
  return (
    <span className={cn("font-mono text-xs", className)}>
      <span className="text-muted-foreground/70">{module}:</span>
      <span className="text-foreground">{method}</span>
    </span>
  );
}

export function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="mb-4 rounded-md border border-bad/40 bg-bad-bg px-3 py-2">
      <HudLabel className="text-bad">repository error</HudLabel>
      <p className="num mt-1 text-xs text-bad">{message}</p>
    </div>
  );
}

export function LoadingBar({ label = "loading" }: { label?: string }) {
  return (
    <div className="flex items-center gap-2 px-1 py-6">
      <span className="signal-dot inline-block h-1.5 w-1.5 rounded-full bg-signal" />
      <HudLabel>{label}</HudLabel>
    </div>
  );
}

export function SectionHeader({
  label,
  right,
  className,
}: {
  label: string;
  right?: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex items-center justify-between border-b border-border px-3 py-2",
        className,
      )}
    >
      <HudLabel>{label}</HudLabel>
      {right}
    </div>
  );
}
