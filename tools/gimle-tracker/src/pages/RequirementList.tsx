import { useMemo, useState, type ReactNode } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useGimleData } from "../DataContext";
import type { Confidence, CoverageTier, RtmStatus } from "../types";
import { CoveragePill, ConfidenceDot, RtmStatusChip, UatMark } from "../components/Badges";

const TIERS: CoverageTier[] = ["e2e", "unit", "none"];
const RTM_STATUSES: RtmStatus[] = ["Active", "New", "Modified", "Removed"];
const CONFIDENCES: Confidence[] = ["High", "Medium", "Low"];

function toggle<T>(set: Set<T>, value: T): Set<T> {
  const next = new Set(set);
  if (next.has(value)) next.delete(value);
  else next.add(value);
  return next;
}

export default function RequirementList() {
  const data = useGimleData();
  const [params, setParams] = useSearchParams();
  const query = params.get("q") ?? "";

  const [tiers, setTiers] = useState<Set<CoverageTier>>(new Set());
  const [rtmStatuses, setRtmStatuses] = useState<Set<RtmStatus>>(new Set());
  const [confidences, setConfidences] = useState<Set<Confidence>>(new Set());
  const [modules, setModules] = useState<Set<string>>(new Set());

  const moduleCounts = useMemo(() => {
    const counts = new Map<string, number>();
    for (const r of data.requirements) counts.set(r.module, (counts.get(r.module) ?? 0) + 1);
    return [...counts.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [data.requirements]);

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return data.requirements.filter((r) => {
      if (tiers.size && !tiers.has(r.tier)) return false;
      if (rtmStatuses.size && !rtmStatuses.has(r.rtmStatus)) return false;
      if (confidences.size && !confidences.has(r.confidence)) return false;
      if (modules.size && !modules.has(r.module)) return false;
      if (q) {
        const hay = `${r.id} ${r.feature} ${r.module} ${r.category} ${r.story}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }, [data.requirements, tiers, rtmStatuses, confidences, modules, query]);

  return (
    <div className="list-layout">
      <aside className="sidebar">
        <FacetGroup title="Coverage" onReset={() => setTiers(new Set())}>
          {TIERS.map((t) => (
            <Chip key={t} active={tiers.has(t)} onClick={() => setTiers(toggle(tiers, t))}>
              {t === "e2e" ? "Holmgang E2E" : t === "unit" ? "Unit only" : "Untested"}
            </Chip>
          ))}
        </FacetGroup>

        <FacetGroup title="RTM status" onReset={() => setRtmStatuses(new Set())}>
          {RTM_STATUSES.map((s) => (
            <Chip key={s} active={rtmStatuses.has(s)} onClick={() => setRtmStatuses(toggle(rtmStatuses, s))}>
              {s}
            </Chip>
          ))}
        </FacetGroup>

        <FacetGroup title="Confidence" onReset={() => setConfidences(new Set())}>
          {CONFIDENCES.map((c) => (
            <Chip key={c} active={confidences.has(c)} onClick={() => setConfidences(toggle(confidences, c))}>
              {c}
            </Chip>
          ))}
        </FacetGroup>

        <FacetGroup title="Module" onReset={() => setModules(new Set())}>
          <div className="modulelist">
            {moduleCounts.map(([name, count]) => (
              <label className="modulelist-row" key={name}>
                <input
                  type="checkbox"
                  checked={modules.has(name)}
                  onChange={() => setModules(toggle(modules, name))}
                />
                <span className="name">{name}</span>
                <span className="count">{count}</span>
              </label>
            ))}
          </div>
        </FacetGroup>
      </aside>

      <div className="list-main">
        <div className="list-toolbar">
          <div className="list-count">
            <b className="mono">{rows.length}</b> requirements
            {query && (
              <>
                {" "}
                matching <b>&ldquo;{query}&rdquo;</b>{" "}
                <button
                  className="facet-clear"
                  onClick={() =>
                    setParams(
                      (prev) => {
                        const next = new URLSearchParams(prev);
                        next.delete("q");
                        return next;
                      },
                      { replace: true },
                    )
                  }
                >
                  clear search
                </button>
              </>
            )}
          </div>
        </div>

        <div className="issue-table">
          <div className="issue-head">
            <span>Key</span>
            <span>Requirement</span>
            <span className="col-module">Module</span>
            <span className="col-status">RTM</span>
            <span>Coverage</span>
            <span style={{ textAlign: "center" }}>Conf.</span>
            <span style={{ textAlign: "center" }}>UAT</span>
          </div>
          {rows.map((r) => (
            <Link className="issue-row" to={`/requirements/${r.id}`} key={r.id}>
              <span className="issue-id mono">{r.id}</span>
              <div className="issue-main">
                <div className="issue-feature">{r.feature}</div>
                <div className="issue-category">{r.category}</div>
              </div>
              <span className="issue-module">{r.module}</span>
              <RtmStatusChip status={r.rtmStatus} />
              <CoveragePill tier={r.tier} />
              <ConfidenceDot confidence={r.confidence} />
              <UatMark signed={r.signedOff} />
            </Link>
          ))}
          {rows.length === 0 && <div className="empty-state">No requirements match the current filters.</div>}
        </div>
      </div>
    </div>
  );
}

function FacetGroup({
  title,
  onReset,
  children,
}: {
  title: string;
  onReset: () => void;
  children: ReactNode;
}) {
  return (
    <div className="facet-group">
      <div className="facet-title">
        {title}
        <button className="facet-clear" onClick={onReset}>
          reset
        </button>
      </div>
      <div className="chiprow">{children}</div>
    </div>
  );
}

function Chip({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button type="button" className="chip" aria-pressed={active} onClick={onClick}>
      {children}
    </button>
  );
}
