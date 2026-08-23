import type { LifecycleState } from "@/types";
import { cn } from "@/lib/utils";

export function StatusDot({
  variant,
  className,
}: {
  variant: "ok" | "warn" | "bad" | "info" | "muted";
  className?: string;
}) {
  const color =
    variant === "ok"
      ? "bg-status-ok"
      : variant === "warn"
        ? "bg-status-warn"
        : variant === "bad"
          ? "bg-status-bad"
          : variant === "info"
            ? "bg-status-info"
            : "bg-status-muted";
  return <span className={cn("inline-block h-2 w-2 rounded-full", color, className)} />;
}

export function StatusBadge({
  variant,
  children,
  className,
  title,
}: {
  variant: "ok" | "warn" | "bad" | "info" | "muted";
  children: React.ReactNode;
  className?: string;
  /** Native tooltip on hover -- e.g. a violation's own reason text, too long for the badge itself. */
  title?: string;
}) {
  const map = {
    ok: "bg-status-ok-bg text-status-ok border-status-ok/30",
    warn: "bg-status-warn-bg text-status-warn border-status-warn/30",
    bad: "bg-status-bad-bg text-status-bad border-status-bad/30",
    info: "bg-status-info-bg text-status-info border-status-info/30",
    muted: "bg-muted text-muted-foreground border-border",
  } as const;
  return (
    <span
      title={title}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-sm border px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wide",
        map[variant],
        className,
      )}
    >
      {children}
    </span>
  );
}

export function LifecycleBadge({ state }: { state: LifecycleState }) {
  const variant =
    state === "ACTIVE" || state === "COMPLETED"
      ? "ok"
      : state === "STARTING" || state === "STOPPING"
        ? "warn"
        : state === "UNINSTALLED" || state === "FAILED"
          ? "bad"
          : "muted";
  return <StatusBadge variant={variant}>{state}</StatusBadge>;
}
