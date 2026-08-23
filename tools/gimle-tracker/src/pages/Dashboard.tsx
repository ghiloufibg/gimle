import { Link } from "react-router-dom";
import { useGimleData } from "../DataContext";

export default function Dashboard() {
  const data = useGimleData();
  const { summary, byModule } = data;

  const totalTested = Object.values(byModule).reduce((sum, m) => sum + m.e2e + m.unit, 0);
  const unitPct = (totalTested / summary.total) * 100;
  const e2ePct = (summary.coveredE2E / summary.total) * 100;
  const signedPct = (summary.signedOff / summary.total) * 100;

  const modules = Object.entries(byModule)
    .map(([name, c]) => ({ name, ...c }))
    .sort((a, b) => b.total - a.total);

  const gaps = data.requirements.filter((r) => r.tier === "none").slice(0, 12);

  return (
    <div className="content-inner">
      <div className="stat-row">
        <StatTile label="Total requirements" value={summary.total} sub={`${summary.moduleCount} modules`} />
        <StatTile
          label="Unit / integration coverage"
          value={unitPct.toFixed(1)}
          unit="%"
          sub={`${totalTested} of ${summary.total} requirements`}
          accent="mid"
        />
        <StatTile
          label="Holmgang E2E coverage"
          value={e2ePct.toFixed(1)}
          unit="%"
          sub={`${summary.coveredE2E} of ${summary.total} requirements`}
          accent="good"
        />
        <StatTile
          label="UAT sign-off"
          value={signedPct.toFixed(1)}
          unit="%"
          sub={`${summary.signedOff} of ${summary.total} requirements`}
          accent="gold"
        />
      </div>

      <div className="panel">
        <div className="panel-head">
          <h2>Coverage by module</h2>
          <span className="hint">Holmgang end-to-end vs. unit/integration vs. untested</span>
        </div>
        <div className="panel-body">
          <div className="legend">
            <span>
              <i style={{ background: "var(--good)" }} />
              Holmgang E2E covered
            </span>
            <span>
              <i style={{ background: "var(--mid)" }} />
              Unit/integration only
            </span>
            <span>
              <i style={{ background: "var(--bad)" }} />
              Untested
            </span>
          </div>
          <div className="modbar-list">
            {modules.map((m) => (
              <div className="modbar-row" key={m.name}>
                <div className="mname">{m.name}</div>
                <div className="modbar-track">
                  {(["e2e", "unit", "none"] as const).map((k) =>
                    m[k] ? (
                      <span
                        key={k}
                        className="seg"
                        style={{
                          width: `${(m[k] / m.total) * 100}%`,
                          background: k === "e2e" ? "var(--good)" : k === "unit" ? "var(--mid)" : "var(--bad)",
                        }}
                        title={`${m[k]} ${k}`}
                      />
                    ) : null,
                  )}
                </div>
                <div className="mtotal">{m.total}</div>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="panel">
        <div className="panel-head">
          <h2>Needs coverage</h2>
          <span className="hint">Untested requirements, first 12</span>
        </div>
        <div className="panel-body">
          <div className="gap-list">
            {gaps.length === 0 && <div className="empty-state">Nothing untested — every requirement has at least unit coverage.</div>}
            {gaps.map((r) => (
              <Link className="gap-row" to={`/requirements/${r.id}`} key={r.id}>
                <span className="id mono">{r.id}</span>
                <span className="gap-feature">{r.feature}</span>
                <span className="gmod">{r.module}</span>
              </Link>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

function StatTile({
  label,
  value,
  unit,
  sub,
  accent,
}: {
  label: string;
  value: string | number;
  unit?: string;
  sub: string;
  accent?: "good" | "mid" | "gold";
}) {
  return (
    <div className={`stat-tile${accent ? ` accent-${accent}` : ""}`}>
      <div className="label">{label}</div>
      <div className="value">
        {value}
        {unit && <span className="unit">{unit}</span>}
      </div>
      <div className="sub mono">{sub}</div>
    </div>
  );
}
