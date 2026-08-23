import type { Confidence, CoverageTier, RtmStatus } from "../types";

const TIER_LABEL: Record<CoverageTier, string> = {
  e2e: "Holmgang E2E",
  unit: "Unit / integration",
  none: "Untested",
};
const TIER_SHORT: Record<CoverageTier, string> = { e2e: "E2E", unit: "Unit", none: "None" };

export function CoveragePill({ tier, full = false }: { tier: CoverageTier; full?: boolean }) {
  return (
    <span className={`pill pill-${tier}`} title={TIER_LABEL[tier]}>
      <i />
      {full ? TIER_LABEL[tier] : TIER_SHORT[tier]}
    </span>
  );
}

export function RtmStatusChip({ status, suffix = "" }: { status: RtmStatus; suffix?: string }) {
  const cls = status === "New" ? "is-new" : status === "Modified" ? "is-modified" : "";
  return (
    <span className={`statuschip ${cls}`.trim()}>
      {status}
      {suffix}
    </span>
  );
}

const CONF_COLOR: Record<Confidence, string> = {
  High: "var(--good)",
  Medium: "var(--mid)",
  Low: "var(--bad)",
};

export function ConfidenceDot({ confidence }: { confidence: Confidence }) {
  return (
    <span
      className="conf-dot"
      style={{ background: CONF_COLOR[confidence] }}
      title={`${confidence} confidence`}
    />
  );
}

export function UatMark({ signed }: { signed: boolean }) {
  return (
    <span className={`uat-mark${signed ? " signed" : ""}`} title={signed ? "Signed off" : "Not signed off"}>
      {signed ? (
        <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
          <path
            d="M2.5 7.3L5.6 10.4L11.5 3.8"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      ) : (
        <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
          <circle cx="7" cy="7" r="4.6" stroke="currentColor" strokeWidth="1.3" strokeDasharray="2 2" />
        </svg>
      )}
    </span>
  );
}
