#!/usr/bin/env python3
"""Regenerates REQUIREMENTS_MATRIX.md, RTM.md, and UAT_CHECKLIST.md from their structured JSON
source of truth (requirements-matrix.json, rtm.json, uat-checklist.json), all at the repo root.

The JSON files ARE the source of truth -- hand-edit (or script-edit) those when adding, changing,
or removing a requirement, then re-run this script to refresh the three generated Markdown views.
Never hand-edit the generated .md files directly; a direct edit is silently discarded the next
time this script runs.

uat-checklist.json carries one piece of state the others don't: per-item UAT sign-off
(signedOff/signedOffBy/signedOffAt). Re-running this script merges rtm.json's current entries
into the existing uat-checklist.json by ID -- an entry's sign-off survives a regeration untouched,
a newly-appeared ID is added unsigned, and an ID no longer in rtm.json (e.g. marked Removed) is
dropped.

Usage:
    python3 scripts/generate_requirements_docs.py
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load(name):
    with open(os.path.join(ROOT, name), encoding="utf-8") as f:
        return json.load(f)


def save(name, data):
    with open(os.path.join(ROOT, name), "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def write_md(name, text):
    with open(os.path.join(ROOT, name), "w", encoding="utf-8") as f:
        f.write(text)


def module_order(requirements):
    """Modules in first-appearance order (requirements are already ID-sequential per module)."""
    seen = []
    for r in requirements:
        if r["module"] not in seen:
            seen.append(r["module"])
    return seen


def group_by_module(requirements):
    by_module = {}
    for r in requirements:
        by_module.setdefault(r["module"], []).append(r)
    return by_module


def esc_cell(s):
    """Escape a value for embedding in a Markdown table cell."""
    return str(s).replace("|", "\\|").replace("\n", " ")


# ---------------------------------------------------------------------------
# REQUIREMENTS_MATRIX.md
# ---------------------------------------------------------------------------
def render_requirements_matrix(doc):
    reqs = doc["requirements"]
    lines = [f"# {doc['title']}", "", doc["preamble"], "", "## Summary Table", ""]
    lines.append("| ID | Feature | Category | Status | Test Coverage |")
    lines.append("|---|---|---|---|---|")
    for r in reqs:
        lines.append(
            f"| {r['id']} | {esc_cell(r['feature'])} | {esc_cell(r['category'])} | "
            f"{esc_cell(r['statusSummary'])} | {esc_cell(r['testCoverageLevel'])} |"
        )
    lines.append("")
    lines.append("## Detailed Requirements")
    lines.append("")

    by_module = group_by_module(reqs)
    for module in module_order(reqs):
        lines.append(f"### {module}")
        lines.append("")
        for r in by_module[module]:
            lines.append(f"#### {r['id']} — {r['feature']}")
            lines.append("")
            lines.append(f"- **Category**: {r['category']}")
            lines.append(f"- **User story**: {r['userStory']}")
            lines.append(f"- **Status**: {r['statusDetail']}")
            lines.append(f"- **Confidence**: {r['confidence']}")
            lines.append(f"- **Source location(s)**: {', '.join(r['sourceLocations'])}")
            lines.append(f"- **Test coverage**: {r['testCoverageDetail']}")
            lines.append("- **Gherkin scenario**:")
            lines.append("  ```gherkin")
            for step in r["gherkinSteps"]:
                lines.append(f"  {step}")
            lines.append("  ```")
            lines.append("")
    return "\n".join(lines).rstrip() + "\n"


# ---------------------------------------------------------------------------
# RTM.md
# ---------------------------------------------------------------------------
def render_rtm(doc):
    md = doc["metadata"]
    reqs = doc["requirements"]
    lines = [f"# {doc['title']}", ""]
    lines.append(
        f"- **Baseline requirements document**: `{md['baselineDocument']}`, generated at commit "
        f"`{md['baselineCommit']}`"
    )
    lines.append(
        f"- **Current HEAD scanned**: `{md['currentHeadCommit']}` (`{md['currentHeadShort']}`), "
        f"branch `{md['branch']}`"
    )
    lines.append(f"- **Rebase acknowledgment**: {md['rebaseAcknowledgment']}")
    lines.append(f"- **Scan date**: {md['scanDate']}")
    lines.append("")
    lines.append("## Coverage rule (strict, as specified)")
    lines.append("")
    lines.append(md["coverageRule"])
    lines.append("")
    lines.append(md["holmgangSurfaceNote"])
    lines.append("")
    lines.append("## Summary Table")
    lines.append("")
    lines.append("| ID | Feature | Status | Coverage | Holmgang scenario reference |")
    lines.append("|---|---|---|---|---|")
    for r in reqs:
        if r["coverage"] == "Covered":
            ref = "; ".join(
                f'`{os.path.basename(s["featureFile"])}` — "{s["scenario"]}"'
                for s in r["holmgangScenarios"]
            )
        else:
            ref = "—"
        lines.append(
            f"| {r['id']} | {esc_cell(r['feature'])} | {esc_cell(r['status'])} | "
            f"{esc_cell(r['coverage'])} | {ref} |"
        )
    lines.append("")
    lines.append("## Detailed Requirements")
    lines.append("")

    by_module = group_by_module(reqs)
    for module in module_order(reqs):
        lines.append(f"### {module}")
        lines.append("")
        for r in by_module[module]:
            lines.append(f"#### {r['id']} — {r['feature']}")
            lines.append("")
            lines.append(f"- **Category**: {r['category']}")
            status_line = r["status"]
            if r["statusNote"]:
                status_line += f"  _({r['statusNote']})_"
            lines.append(f"- **Status**: {status_line}")
            lines.append(f"- **Coverage**: {r['coverage']}")
            if r["coverage"] == "Covered":
                lines.append("- **Holmgang feature file(s) + scenario(s)**:")
                for s in r["holmgangScenarios"]:
                    lines.append(f"  - `{s['featureFile']}` — Scenario: *{s['scenario']}*")
                    lines.append(f"  - _Why this counts_: {s['whyThisCounts']}")
            else:
                lines.append(f"- **Gap note**: {r['gapNote']}")
            lines.append(
                f"- **Other test coverage (non-Holmgang, informational only)**: "
                f"{r['otherTestCoverage']}"
            )
            lines.append(f"- **Source location(s)**: {', '.join(r['sourceLocations'])}")
            lines.append("")
    lines.append("## Coverage Gaps — Release-Readiness Checklist")
    lines.append("")
    not_covered = [r for r in reqs if r["coverage"] != "Covered"]
    lines.append(
        "Every requirement below has **no** Holmgang Cucumber scenario exercising it, per the "
        "strict rule. Sorted by Category. This is the checklist: closing a row means either "
        "adding/extending a Holmgang scenario (see each row's Gap note for the shape) or making "
        "a deliberate, recorded decision that a given capability does not warrant real-cluster "
        "Cucumber coverage (e.g. pure build tooling, console frontend behavior, or low-level "
        "wire-codec internals — flagged as such in the Gap note itself)."
    )
    lines.append("")
    lines.append(f"**{len(not_covered)} of {len(reqs)} requirements are Not Covered.**")
    lines.append("")
    lines.append("| ID | Module | Feature | Category | Other test coverage (non-Holmgang) |")
    lines.append("|---|---|---|---|---|")
    for r in sorted(not_covered, key=lambda r: (r["category"], r["id"])):
        lines.append(
            f"| {r['id']} | {r['module']} | {esc_cell(r['feature'])} | {esc_cell(r['category'])} | "
            f"{esc_cell(r['otherTestCoverage'] or 'None')} |"
        )
    return "\n".join(lines).rstrip() + "\n"


# ---------------------------------------------------------------------------
# UAT_CHECKLIST.md + uat-checklist.json
# ---------------------------------------------------------------------------
def build_uat_checklist(rm_doc, rtm_doc, existing_uat):
    reqs = [r for r in rtm_doc["requirements"] if r["status"] != "Removed"]
    prior_by_id = {i["id"]: i for i in (existing_uat or {}).get("items", [])}
    gherkin_by_id = {r["id"]: r["gherkinSteps"] for r in rm_doc["requirements"]}

    items = []
    for r in reqs:
        # Prefer the requirement's own baseline Gherkin scenario (the canonical acceptance-test
        # step) over anything re-derived from RTM.md's coverage prose -- same source a manual
        # tester would want regardless of whether Holmgang happens to cover it too.
        steps = gherkin_by_id.get(r["id"])
        if steps:
            step = " ".join(steps)
        elif r["coverage"] == "Covered" and r["holmgangScenarios"]:
            s = r["holmgangScenarios"][0]
            step = f'{s["scenario"]} (see `{os.path.basename(s["featureFile"])}`)'
        elif r["gapNote"]:
            first_sentence = r["gapNote"].split(". ")[0].rstrip(".") + "."
            step = f"Manually verify: {r['feature']}. {first_sentence}"
        else:
            step = f"Manually verify: {r['feature']}."

        prior = prior_by_id.get(r["id"], {})
        items.append(
            {
                "id": r["id"],
                "module": r["module"],
                "category": r["category"],
                "feature": r["feature"],
                "step": step,
                "automatedCoverage": r["coverage"] == "Covered",
                "signedOff": prior.get("signedOff", False),
                "signedOffBy": prior.get("signedOffBy"),
                "signedOffAt": prior.get("signedOffAt"),
            }
        )

    total = len(items)
    covered = sum(1 for i in items if i["automatedCoverage"])
    return {
        "generatedFrom": "rtm.json",
        "summary": {
            "totalRequirements": total,
            "coveredByAutomatedTest": covered,
            "notCoveredByAutomatedTest": total - covered,
            "releaseReadinessPercent": round(100.0 * covered / total, 1) if total else 0.0,
        },
        "items": items,
    }


def render_uat_checklist_md(uat):
    s = uat["summary"]
    lines = ["# Gimlé User Acceptance Test (UAT) Checklist", ""]
    lines.append(
        "Derived from `rtm.json` (the Holmgang-Cucumber-coverage-validated Requirements "
        "Traceability Matrix), itself validated against `requirements-matrix.json`. Every "
        "requirement below has `Status` other than `Removed` -- there is nothing left to "
        "acceptance-test for a removed requirement. Regenerated by "
        "`scripts/generate_requirements_docs.py`; **sign-off state lives in `uat-checklist.json`, "
        "not here** -- edit sign-off there (or via whatever UAT tooling reads/writes it) and "
        "re-run the generator, don't hand-edit the checkboxes in this file."
    )
    lines.append("")
    lines.append(
        "**\"Covered by automated test\"** reflects `rtm.json`'s strict Coverage field: `true` "
        "only if a real Cucumber `.feature` scenario in `gimle-holmgang` exercises this "
        "requirement against a real running cluster. A unit test, integration test, or one of "
        "Holmgang's own plain-JUnit `*IT` classes does **not** count."
    )
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append(f"- **Total requirements**: {s['totalRequirements']}")
    lines.append(f"- **Covered by automated (Holmgang Cucumber) test**: {s['coveredByAutomatedTest']}")
    lines.append(f"- **Not covered by automated test**: {s['notCoveredByAutomatedTest']}")
    lines.append(f"- **Release-readiness (automated coverage)**: {s['releaseReadinessPercent']}%")
    lines.append("")

    by_module = {}
    for i in uat["items"]:
        by_module.setdefault(i["module"], []).append(i)
    mods = []
    for i in uat["items"]:
        if i["module"] not in mods:
            mods.append(i["module"])

    lines.append("| Module | Requirements | Covered | Not Covered | Coverage % |")
    lines.append("|---|---|---|---|---|")
    for module in mods:
        mitems = by_module[module]
        mcov = sum(1 for i in mitems if i["automatedCoverage"])
        mtotal = len(mitems)
        pct = round(100.0 * mcov / mtotal, 1) if mtotal else 0.0
        lines.append(f"| {module} | {mtotal} | {mcov} | {mtotal - mcov} | {pct}% |")
    lines.append("")
    lines.append("## Checklist")
    lines.append("")

    for module in mods:
        lines.append(f"### {module}")
        lines.append("")
        by_cat = {}
        for i in by_module[module]:
            by_cat.setdefault(i["category"], []).append(i)
        for category in sorted(by_cat.keys()):
            lines.append(f"#### {category}")
            lines.append("")
            lines.append("| Sign-off | ID | Feature | Test Step | Covered by automated test |")
            lines.append("|---|---|---|---|---|")
            for i in sorted(by_cat[category], key=lambda x: x["id"]):
                box = "[x]" if i["signedOff"] else "[ ]"
                covered_flag = "Yes" if i["automatedCoverage"] else "No"
                lines.append(
                    f"| {box} | {i['id']} | {esc_cell(i['feature'])} | {esc_cell(i['step'])} | "
                    f"{covered_flag} |"
                )
            lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def main():
    rm_doc = load("requirements-matrix.json")
    rtm_doc = load("rtm.json")

    write_md("REQUIREMENTS_MATRIX.md", render_requirements_matrix(rm_doc))
    print(f"Wrote REQUIREMENTS_MATRIX.md ({rm_doc['requirementCount']} requirements)")

    write_md("RTM.md", render_rtm(rtm_doc))
    print(f"Wrote RTM.md ({rtm_doc['requirementCount']} requirements)")

    try:
        existing_uat = load("uat-checklist.json")
    except FileNotFoundError:
        existing_uat = None
    uat = build_uat_checklist(rm_doc, rtm_doc, existing_uat)
    save("uat-checklist.json", uat)
    write_md("UAT_CHECKLIST.md", render_uat_checklist_md(uat))
    print(f"Wrote uat-checklist.json + UAT_CHECKLIST.md ({uat['summary']['totalRequirements']} items)")


if __name__ == "__main__":
    main()
