#!/usr/bin/env python3
"""Regenerates the generated sections of FORSETI.md from forseti.json, requirements-matrix.json and
rtm.json (all at the repo root).

forseti.json is the source of truth for the fleet scenario catalog and for which requirements are
internal or out of scope; requirements-matrix.json is the source of truth for the requirements
themselves; rtm.json supplies the unit-test and Holmgang citations every non-fleet row falls back to.
Only the blocks between `<!-- forseti:generated <name> -->` and `<!-- /forseti:generated -->`
markers in FORSETI.md are rewritten -- the doctrine prose around them is hand-authored and left
untouched. Never hand-edit the generated blocks; a direct edit is silently discarded the next time
this script runs.

Exits non-zero (after still writing the file) when a requirement is user-observable but reached by
no fleet scenario and carries no unit/Holmgang citation either, or when fleet coverage of the
user-observable set falls below forseti.json's fleetCoverageTarget -- so a new GIMLE-NNN that nobody
placed in forseti.json is loud, not silent.

Usage:
    python3 scripts/generate_forseti_docs.py
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = "FORSETI.md"


def load(name):
    with open(os.path.join(ROOT, name), encoding="utf-8") as f:
        return json.load(f)


def esc(s):
    return str(s).replace("|", "\\|").replace("\n", " ")


def req_num(rid):
    return int(rid.split("-")[1])


# ---------------------------------------------------------------------------
# Coverage model
# ---------------------------------------------------------------------------
def build_model(forseti, matrix, rtm):
    reqs = {r["id"]: r for r in matrix["requirements"]}
    rtm_by_id = {r["id"]: r for r in rtm["requirements"]}

    classified = {}
    for bucket in ("outOfScope", "internal"):
        for key, group in forseti["classification"][bucket].items():
            for rid in group["ids"]:
                if rid not in reqs:
                    raise SystemExit(f"forseti.json classifies unknown requirement {rid}")
                if rid in classified:
                    raise SystemExit(f"{rid} classified twice: {classified[rid]} and {(bucket, key)}")
                classified[rid] = (bucket, key)

    scenarios_by_req = {}
    for s in forseti["scenarios"]:
        for rid in s["requirements"]:
            if rid not in reqs:
                raise SystemExit(f"scenario {s['id']} names unknown requirement {rid}")
            if rid in classified:
                raise SystemExit(
                    f"scenario {s['id']} reaches {rid}, which forseti.json also classifies as "
                    f"{classified[rid][1]} -- pick one"
                )
            scenarios_by_req.setdefault(rid, []).append(s["id"])

    rows = []
    for rid in sorted(reqs, key=req_num):
        r = reqs[rid]
        t = rtm_by_id.get(rid, {})
        holmgang = t.get("holmgangScenarios") or []
        unit = (t.get("otherTestCoverage") or "").strip()
        if rid in classified:
            bucket, key = classified[rid]
            group = "out-of-scope" if bucket == "outOfScope" else "internal"
        else:
            bucket, key, group = None, None, "observable"
        if group == "observable" and rid in scenarios_by_req:
            mechanism, evidence = "FLEET", ", ".join(scenarios_by_req[rid])
        elif group == "out-of-scope":
            mechanism, evidence = "OUT OF SCOPE", key
        elif holmgang:
            mechanism = "HOLMGANG"
            evidence = "; ".join(
                f"`{os.path.basename(h['featureFile'])}` — {h['scenario']}" if isinstance(h, dict) else str(h)
                for h in holmgang
            )
        elif unit:
            mechanism, evidence = "UNIT", unit
        else:
            mechanism, evidence = "UNCOVERED", "no fleet scenario, no Holmgang scenario, no unit test cited"
        rows.append({
            "id": rid, "feature": r["feature"], "module": r["module"], "group": group,
            "classificationKey": key, "mechanism": mechanism, "evidence": evidence,
        })
    return rows


def summarize(rows, target):
    total = len(rows)
    oos = [r for r in rows if r["group"] == "out-of-scope"]
    internal = [r for r in rows if r["group"] == "internal"]
    observable = [r for r in rows if r["group"] == "observable"]
    fleet = [r for r in observable if r["mechanism"] == "FLEET"]
    residual = [r for r in observable if r["mechanism"] != "FLEET"]
    uncovered = [r for r in rows if r["mechanism"] == "UNCOVERED"]
    pct = (len(fleet) / len(observable)) if observable else 0.0
    return {
        "total": total, "outOfScope": len(oos), "internal": len(internal), "observable": len(observable),
        "fleet": len(fleet), "fleetPct": pct, "residual": residual, "uncovered": uncovered,
        "target": target, "meetsTarget": pct >= target,
        "internalByMechanism": {m: sum(1 for r in internal if r["mechanism"] == m) for m in ("HOLMGANG", "UNIT", "UNCOVERED")},
    }


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------
def render_summary(s):
    verdict = "meets" if s["meetsTarget"] else "**FALLS SHORT OF**"
    lines = [
        "| Bucket | Count | Meaning |", "|---|---:|---|",
        f"| Requirements in `requirements-matrix.json` | {s['total']} | The whole denominator before any classification. |",
        f"| Out of scope | {s['outOfScope']} | Not built, a documented limitation, or a test asset itself — see the exclusions table. |",
        f"| Internal | {s['internal']} | Real platform behaviour a user cannot observe from outside a process; every row carries its unit-test or Holmgang citation (Holmgang {s['internalByMechanism']['HOLMGANG']}, unit {s['internalByMechanism']['UNIT']}, uncited {s['internalByMechanism']['UNCOVERED']}). |",
        f"| **User-observable** | **{s['observable']}** | The capability set the fleet is measured against. |",
        f"| Reached by a fleet scenario | {s['fleet']} | **{s['fleetPct']*100:.1f}%** of the user-observable set — {verdict} the {s['target']*100:.0f}% target. |",
        f"| Observable, not fleet-reached | {len(s['residual'])} | Each carries its unit/Holmgang citation in the residual table. |",
    ]
    if s["uncovered"]:
        lines.append("")
        lines.append(f"**{len(s['uncovered'])} requirement(s) have no mechanism at all** — fix before the next release pass: "
                     + ", ".join(r["id"] for r in s["uncovered"]) + ".")
    return "\n".join(lines)


def render_exclusions(forseti, rows):
    by_key = {}
    for r in rows:
        if r["group"] == "out-of-scope":
            by_key.setdefault(r["classificationKey"], []).append(r["id"])
    lines = ["| Reason | Why it is excluded | Requirements |", "|---|---|---|"]
    for key, group in forseti["classification"]["outOfScope"].items():
        lines.append(f"| `{key}` | {esc(group['note'])} | {len(by_key.get(key, []))}: {', '.join(by_key.get(key, []))} |")
    return "\n".join(lines)


def render_internal_groups(forseti, rows):
    by_key = {}
    for r in rows:
        if r["group"] == "internal":
            by_key.setdefault(r["classificationKey"], []).append(r)
    lines = ["| Group | Why a black-box tester cannot reach it | Requirements | Cited by |", "|---|---|---:|---|"]
    for key, group in forseti["classification"]["internal"].items():
        members = by_key.get(key, [])
        h = sum(1 for r in members if r["mechanism"] == "HOLMGANG")
        u = sum(1 for r in members if r["mechanism"] == "UNIT")
        x = sum(1 for r in members if r["mechanism"] == "UNCOVERED")
        cited = f"Holmgang {h}, unit {u}" + (f", **uncited {x}**" if x else "")
        lines.append(f"| `{key}` | {esc(group['note'])} | {len(members)} | {cited} |")
    return "\n".join(lines)


def render_residual(s):
    if not s["residual"]:
        return "_Every user-observable requirement is currently reached by at least one fleet scenario; nothing to list._"
    lines = ["| ID | Feature | Mechanism | Evidence |", "|---|---|---|---|"]
    for r in s["residual"]:
        lines.append(f"| {r['id']} | {esc(r['feature'])} | {r['mechanism']} | {esc(r['evidence'])} |")
    return "\n".join(lines)


def render_environments(forseti):
    lines = ["| Env | Shape | Built via | What only this environment can test |", "|---|---|---|---|"]
    for e in forseti["environments"]:
        lines.append(f"| **{e['name']}** ({e['letter']}) | {esc(e['shape'])} | {esc(e['builtVia'])} | {esc(e['covers'])} |")
    return "\n".join(lines)


def render_roster(forseti, rows):
    env_letter = {e["id"]: e["letter"] for e in forseti["environments"]}
    fleet_reqs = {}
    per_persona = {}
    for sc in forseti["scenarios"]:
        per_persona.setdefault(sc["persona"], []).append(sc)
        for rid in sc["requirements"]:
            fleet_reqs.setdefault(sc["persona"], set()).add(rid)
    lines = ["| ID | Persona | Who they are | Environments | Scenarios | Requirements reached |", "|---|---|---|---|---:|---:|"]
    for p in forseti["personas"]:
        envs = " ".join(env_letter[e] for e in p["environments"]) or "—"
        n = len(per_persona.get(p["id"], []))
        lines.append(f"| **{p['id']}** | {esc(p['name'])} | {esc(p['who'])} | {envs} | {n if n else '—'} | {len(fleet_reqs.get(p['id'], ())) or '—'} |")
    return "\n".join(lines)


def render_catalog(forseti):
    env_letter = {e["id"]: e["letter"] for e in forseti["environments"]}
    persona_name = {p["id"]: p["name"] for p in forseti["personas"]}
    out = []
    for p in forseti["personas"]:
        scs = [s for s in forseti["scenarios"] if s["persona"] == p["id"]]
        if not scs:
            continue
        out.append(f"#### {persona_name[p['id']]}")
        out.append("")
        out.append("| ID | Env | Objective | Oracle | Requirements |")
        out.append("|---|---|---|---|---|")
        for s in scs:
            envs = " ".join(env_letter[e] for e in s["environments"])
            out.append(f"| **{s['id']}** | {envs} | {esc(s['objective'])} | {esc(s['oracle'])} | {', '.join(s['requirements'])} |")
        out.append("")
    return "\n".join(out).rstrip()


def render_full_table(rows):
    lines = ["| ID | Module | Feature | Class | Mechanism | Evidence |", "|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(f"| {r['id']} | `{r['module']}` | {esc(r['feature'])} | {r['group']} | {r['mechanism']} | {esc(r['evidence'])} |")
    return "\n".join(lines)


def splice(text, name, body):
    # The body match is lazy and may be empty, so an empty block on a fresh file never runs on
    # into the next block's markers.
    pattern = re.compile(
        rf"(<!-- forseti:generated {re.escape(name)} -->\n).*?(<!-- /forseti:generated -->)", re.DOTALL
    )
    if not pattern.search(text):
        raise SystemExit(f"FORSETI.md has no generated block named '{name}'")
    return pattern.sub(lambda m: m.group(1) + body + "\n" + m.group(2), text, count=1)


def main():
    forseti = load("forseti.json")
    matrix = load("requirements-matrix.json")
    rtm = load("rtm.json")
    rows = build_model(forseti, matrix, rtm)
    summary = summarize(rows, forseti["fleetCoverageTarget"])

    path = os.path.join(ROOT, DOC)
    with open(path, encoding="utf-8") as f:
        text = f.read()
    text = splice(text, "coverage-summary", render_summary(summary))
    text = splice(text, "environments", render_environments(forseti))
    text = splice(text, "roster", render_roster(forseti, rows))
    text = splice(text, "scenario-catalog", render_catalog(forseti))
    text = splice(text, "residual", render_residual(summary))
    text = splice(text, "exclusions", render_exclusions(forseti, rows))
    text = splice(text, "internal-groups", render_internal_groups(forseti, rows))
    text = splice(text, "coverage-table", render_full_table(rows))
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)

    print(f"{DOC}: {summary['observable']} user-observable requirements, {summary['fleet']} fleet-reached "
          f"({summary['fleetPct']*100:.1f}%, target {summary['target']*100:.0f}%), "
          f"{len(summary['residual'])} residual, {summary['internal']} internal, {summary['outOfScope']} out of scope")
    problems = []
    if not summary["meetsTarget"]:
        problems.append("fleet coverage below target")
    if summary["uncovered"]:
        problems.append("uncited requirements: " + ", ".join(r["id"] for r in summary["uncovered"]))
    if problems:
        print("FORSETI GAP: " + "; ".join(problems), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
