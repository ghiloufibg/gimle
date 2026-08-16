import type { ChaosStrike, GherkinFeature, SurtrPhase } from "@/domain/types";
import { formatDuration } from "@/lib/format";
import { Chip, HudLabel, OutcomeDot } from "./primitives";

export function GherkinTree({ features }: { features: GherkinFeature[] }) {
  return (
    <div className="divide-y divide-border/60">
      {features.map((feature) => (
        <div key={feature.name} className="px-3 py-3">
          <div className="flex items-center gap-2">
            <HudLabel>feature</HudLabel>
            <span className="text-sm font-medium text-foreground">{feature.name}</span>
            <Chip tone="info">{feature.module.replace("gimle-", "")}</Chip>
          </div>
          <div className="mt-2 space-y-2 border-l border-border pl-3">
            {feature.scenarios.map((scenario) => (
              <div key={scenario.name}>
                <div className="flex items-center gap-2">
                  <OutcomeDot outcome={scenario.status} />
                  <span className="text-xs font-medium text-foreground">{scenario.name}</span>
                </div>
                <ul className="mt-1 space-y-0.5 border-l border-border/60 pl-3">
                  {scenario.steps.map((step, i) => (
                    <li key={i} className="flex items-center gap-2">
                      <OutcomeDot outcome={step.status} />
                      <span className="num text-[11px] text-warn">{step.keyword}</span>
                      <span className="num flex-1 truncate text-[11px] text-muted-foreground">
                        {step.text}
                      </span>
                      <span className="num text-[10px] text-muted-foreground/70">
                        {formatDuration(step.durationMillis)}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

export function ChaosLedger({ strikes }: { strikes: ChaosStrike[] }) {
  const span = Math.max(...strikes.map((s) => s.atMillis + s.recoveryMillis), 1);
  return (
    <div className="px-3 py-3">
      <div className="space-y-2">
        {strikes.map((strike, i) => (
          <div key={i} className="grid grid-cols-[9rem_1fr_5rem] items-center gap-3">
            <div>
              <div className="num text-[11px] text-foreground">{strike.kind}</div>
              <div className="num text-[10px] text-muted-foreground">{strike.target}</div>
            </div>
            <div className="relative h-3 rounded-sm bg-muted">
              <div
                style={{
                  left: `${(strike.atMillis / span) * 100}%`,
                  width: `${Math.max(1.5, (strike.recoveryMillis / span) * 100)}%`,
                }}
                className={`absolute inset-y-0 rounded-sm ${
                  strike.recovered ? "bg-warn/70" : "bg-bad"
                }`}
              />
            </div>
            <div className="text-right">
              <span className={`num text-[11px] ${strike.recovered ? "text-warn" : "text-bad"}`}>
                {formatDuration(strike.recoveryMillis)}
              </span>
            </div>
          </div>
        ))}
      </div>
      <div className="mt-3 flex justify-between">
        <HudLabel>t0</HudLabel>
        <HudLabel>{formatDuration(span)}</HudLabel>
      </div>
    </div>
  );
}

export function SurtrTable({ phases }: { phases: SurtrPhase[] }) {
  return (
    <table className="w-full border-collapse text-left">
      <thead>
        <tr className="border-b border-border bg-muted/30">
          {["phase", "duration", "p50", "p99", "throughput", "failures"].map((h) => (
            <th key={h} className="px-3 py-1.5">
              <HudLabel>{h}</HudLabel>
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {phases.map((p) => (
          <tr key={p.phase} className="border-b border-border/60 last:border-0">
            <td className="num px-3 py-1.5 text-xs text-foreground">{p.phase}</td>
            <td className="num px-3 py-1.5 text-xs text-muted-foreground">
              {formatDuration(p.durationMillis)}
            </td>
            <td className="num px-3 py-1.5 text-xs text-foreground">{p.p50Millis}ms</td>
            <td className="num px-3 py-1.5 text-xs text-warn">{p.p99Millis}ms</td>
            <td className="num px-3 py-1.5 text-xs text-muted-foreground">{p.throughput} req/s</td>
            <td className={`num px-3 py-1.5 text-xs ${p.failures ? "text-bad" : "text-ok"}`}>
              {p.failures}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
