/** Shared recharts chrome for the HUD charts (metrics, traces) -- the same ChartTooltip/AXIS
 * pair metrics.tsx originally defined locally, extracted so metrics-history-panel.tsx can share
 * it without a circular import back into the route file. */

export function ChartTooltip({
  active,
  payload,
}: {
  active?: boolean;
  payload?: Array<{ payload: Record<string, unknown> }>;
}) {
  if (!active || !payload?.length) return null;
  const p = payload[0].payload as Record<string, unknown>;
  const rows = (p.__tip as Array<[string, string]>) ?? [];
  return (
    <div className="hud-panel rounded-sm border border-primary/30 bg-background/95 px-2 py-1.5 font-mono text-[10px]">
      <div className="mb-0.5 text-signal">{String(p.__label ?? "")}</div>
      {rows.map(([k, v]) => (
        <div key={k} className="flex justify-between gap-3 text-muted-foreground">
          <span className="uppercase tracking-widest">{k}</span>
          <span className="tabular-nums text-foreground">{v}</span>
        </div>
      ))}
    </div>
  );
}

export const AXIS = {
  stroke: "var(--status-muted)",
  fontSize: 10,
  fontFamily: "var(--font-mono, monospace)",
} as const;
