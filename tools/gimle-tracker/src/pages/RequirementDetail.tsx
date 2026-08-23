import { Link, useParams } from "react-router-dom";
import { useGimleData } from "../DataContext";
import { CoveragePill, RtmStatusChip } from "../components/Badges";

function gherkinParts(step: string): { keyword: string; rest: string } {
  const match = step.match(/^(Given|When|Then|And)\b(.*)$/);
  return match ? { keyword: match[1], rest: match[2] } : { keyword: "", rest: step };
}

export default function RequirementDetail() {
  const { id } = useParams<{ id: string }>();
  const data = useGimleData();
  const r = data.requirements.find((x) => x.id === id);

  if (!r) {
    return (
      <div className="content-inner">
        <Link className="back-link" to="/requirements">
          ← All requirements
        </Link>
        <div className="gapbox" style={{ marginTop: 16 }}>
          No requirement with id "{id}" in the loaded data set.
        </div>
      </div>
    );
  }

  return (
    <div className="content-inner">
      <Link className="back-link" to="/requirements">
        ← All requirements
      </Link>
      <div className="detail-crumb">
        {r.module} / {r.category}
      </div>
      <h1 className="detail-title">
        <span className="idmark">{r.id}</span>
        {r.feature}
      </h1>
      <div className="detail-pills">
        <CoveragePill tier={r.tier} full />
        <RtmStatusChip status={r.rtmStatus} suffix=" in RTM" />
        <span className="statuschip">{r.status}</span>
      </div>

      <div className="detail-grid">
        <div className="detail-main">
          <section className="dsection">
            <h3>User story</h3>
            <div className="story">{r.story}</div>
            {r.statusDetail && <div className="confidence-note" style={{ marginTop: 8 }}>{r.statusDetail}</div>}
          </section>

          {r.gherkin.length > 0 && (
            <section className="dsection">
              <h3>Acceptance scenario</h3>
              <div className="gherkin">
                {r.gherkin.map((step, i) => {
                  const { keyword, rest } = gherkinParts(step);
                  return (
                    <div key={i}>
                      {keyword && <span className="kw">{keyword}</span>}
                      {rest}
                    </div>
                  );
                })}
              </div>
            </section>
          )}

          <section className="dsection">
            <h3>Holmgang end-to-end coverage</h3>
            {r.scenarios.length > 0 ? (
              r.scenarios.map((sc, i) => (
                <div className="scenario-card" key={i}>
                  <div className="sfile mono">{sc.file}</div>
                  <div className="sname">{sc.scenario}</div>
                  <div className="swhy">{sc.why}</div>
                </div>
              ))
            ) : (
              <div className="gapbox">
                {r.gapNote || "No Holmgang scenario currently exercises this requirement end to end against a real cluster."}
              </div>
            )}
          </section>

          {r.unitTests && (
            <section className="dsection">
              <h3>Other test coverage</h3>
              <div className="unittests">{r.unitTests}</div>
            </section>
          )}

          {r.src.length > 0 && (
            <section className="dsection">
              <h3>Source</h3>
              <div className="srclist">
                {r.src.map((path) => (
                  <div className="srcitem" key={path}>
                    {path}
                  </div>
                ))}
              </div>
            </section>
          )}

          <section className="dsection">
            <h3>UAT</h3>
            <div className="uat-block">
              <div className="uat-step">{r.uatStep || r.gherkin[0] || r.story}</div>
              <div className="uat-actions">
                <span className={`statuschip${r.signedOff ? " is-new" : ""}`}>
                  {r.signedOff ? "Signed off" : "Not signed off"}
                </span>
                {r.signedOff && r.signedOffBy && (
                  <span className="signoff-hint mono">
                    {r.signedOffBy}
                    {r.signedOffAt ? ` · ${r.signedOffAt}` : ""}
                  </span>
                )}
              </div>
            </div>
          </section>
        </div>

        <aside className="detail-side">
          <div className="side-card">
            <h4>Details</h4>
            <SideRow k="Status" v={r.status} />
            <SideRow k="Module" v={r.module} />
            <SideRow k="Category" v={r.category} />
            <SideRow k="RTM status" v={r.rtmStatus} />
            <SideRow k="Confidence" v={r.confidence} />
            <SideRow k="Coverage" v={r.coverage} />
          </div>
        </aside>
      </div>
    </div>
  );
}

function SideRow({ k, v }: { k: string; v: string }) {
  return (
    <div className="side-row">
      <span className="k">{k}</span>
      <span className="v">{v}</span>
    </div>
  );
}
