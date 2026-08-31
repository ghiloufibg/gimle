import { cn } from "@/lib/utils";

/** Shared "used vs allowance" gauge -- lifted out of metrics.tsx (its original, still-only
 * consumer via the tenant quota pressure panel) so the Tenants screens can render the same
 * server-computed usage/quota numbers the same way. */
export function Bullet({
  label,
  used,
  max,
  display,
}: {
  label: string;
  used: number;
  max: number;
  display: string;
}) {
  const pct = Math.round((used / Math.max(1, max)) * 100);
  const over = pct > 100;
  return (
    <div className="mb-1.5 last:mb-0">
      <div className="flex items-center justify-between font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
        <span>{label}</span>
        <span className={cn("tabular-nums", over ? "text-status-bad" : "text-foreground")}>
          {display} · {pct}%
        </span>
      </div>
      <div className="relative mt-1 h-1.5 overflow-hidden rounded-full bg-muted">
        <div
          className={cn(
            "h-full",
            over ? "bg-status-bad" : pct >= 80 ? "bg-status-warn" : "bg-primary",
          )}
          style={{ width: `${Math.min(100, pct)}%` }}
        />
        <span className="absolute inset-y-0 left-[80%] w-px bg-foreground/25" />
      </div>
    </div>
  );
}
