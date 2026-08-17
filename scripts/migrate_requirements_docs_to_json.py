#!/usr/bin/env python3
"""One-time extraction: parse the existing REQUIREMENTS_MATRIX.md and RTM.md prose/tables into
structured JSON (requirements-matrix.json, rtm.json). Run once to bootstrap the JSON source of
truth; afterwards the JSON files ARE the source and this script is no longer needed for normal
maintenance (see scripts/generate_requirements_docs.py for the JSON -> Markdown renderer that
replaces it going forward)."""
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def field(body, name):
    m = re.search(rf'^-\s+\*\*{re.escape(name)}\*\*:\s*(.*)$', body, re.M)
    return m.group(1).strip() if m else None


def split_table_row(line):
    """Splits a markdown table row into stripped cells, dropping the empty leading/trailing cells
    the row's own bounding pipes produce. No escaped-pipe handling -- neither source document uses
    it (verified: zero literal `\\|` occurrences in either file)."""
    raw = line.strip().split("|")
    return [c.strip() for c in raw[1:-1]]


def split_locations(raw):
    """Best-effort split of a Source location(s) cell into a list. Falls back to a single-element
    list containing the raw text when the content isn't cleanly comma-separated backtick paths
    (some entries append prose annotations) -- no information is dropped either way."""
    if raw is None:
        return []
    # Only split on top-level commas (not inside parens) when every resulting piece starts with a
    # backtick -- the common, unambiguous case. Otherwise keep the whole string as one entry.
    pieces = []
    depth = 0
    current = ""
    for ch in raw:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            pieces.append(current.strip())
            current = ""
        else:
            current += ch
    pieces.append(current.strip())
    if pieces and all(p.startswith("`") for p in pieces):
        return pieces
    return [raw.strip()]


# ---------------------------------------------------------------------------
# REQUIREMENTS_MATRIX.md
# ---------------------------------------------------------------------------
def extract_requirements_matrix():
    text = open(f"{ROOT}/REQUIREMENTS_MATRIX.md", encoding="utf-8").read()

    preamble_end = text.index("## Summary Table")
    title_m = re.match(r'^# (.+)$', text, re.M)
    title = title_m.group(1).strip()
    preamble = text[title_m.end():preamble_end].strip()

    detail_start = text.index("## Detailed Requirements")
    module_re = re.compile(r'^### (.+)$', re.M)
    mods = list(module_re.finditer(text, detail_start))

    entry_re = re.compile(r'^#### (GIMLE-\d+) — (.+?)\s*$(.*?)(?=^#### GIMLE-|\Z)', re.M | re.S)

    requirements = []
    for i, mm in enumerate(mods):
        module = mm.group(1).strip()
        block_start = mm.end()
        block_end = mods[i + 1].start() if i + 1 < len(mods) else len(text)
        module_block = text[block_start:block_end]

        for em in entry_re.finditer(module_block):
            gid, feature, body = em.group(1), em.group(2).strip(), em.group(3)

            category = field(body, "Category") or ""
            user_story = field(body, "User story") or ""
            # The Detailed Requirements section's own Status line is free text (e.g. "Complete
            # (enum); TIER_3 enforcement Partial -- see gimle-os") and is NOT the same string as
            # the Summary Table's Status column for the same ID (which is always a clean
            # Complete/Partial enum) -- the two are captured separately below, never derived from
            # each other via string-splitting (an em-dash can legitimately appear inside the
            # detail text itself, e.g. "...opt-in -- deliberate)", which broke an earlier attempt
            # to split on " -- " to recover a "note").
            status_detail = field(body, "Status") or ""
            confidence = field(body, "Confidence") or ""
            source_raw = field(body, "Source location(s)")
            test_coverage_detail = field(body, "Test coverage") or ""

            gh = re.search(r'```gherkin\s*\n(.*?)\n\s*```', body, re.S)
            gherkin_steps = []
            if gh:
                gherkin_steps = [line.strip() for line in gh.group(1).splitlines() if line.strip()]

            requirements.append(
                {
                    "id": gid,
                    "module": module,
                    "feature": feature,
                    "category": category,
                    "userStory": user_story,
                    "statusDetail": status_detail,
                    "confidence": confidence,
                    "sourceLocations": split_locations(source_raw),
                    "testCoverageDetail": test_coverage_detail,
                    "gherkinSteps": gherkin_steps,
                }
            )

    # The Summary Table carries two columns with no free-text equivalent in the Detailed
    # Requirements section: the clean Complete/Partial Status enum and the Yes/Partial/None Test
    # Coverage level.
    summary_start = text.index("## Summary Table")
    summary_end = detail_start
    summary_block = text[summary_start:summary_end]
    status_by_id = {}
    level_by_id = {}
    for line in summary_block.splitlines():
        line = line.strip()
        if not line.startswith("| GIMLE-"):
            continue
        cells = split_table_row(line)
        if len(cells) != 5:
            continue
        gid, _feature, _category, status_summary, coverage_level = cells
        status_by_id[gid] = status_summary
        level_by_id[gid] = coverage_level
    for r in requirements:
        r["statusSummary"] = status_by_id.get(r["id"], "")
        r["testCoverageLevel"] = level_by_id.get(r["id"], "")

    ids = [r["id"] for r in requirements]
    assert len(ids) == len(set(ids)), "duplicate IDs found"

    doc = {
        "title": title,
        "preamble": preamble,
        "requirementCount": len(requirements),
        "requirements": requirements,
    }
    return doc


# ---------------------------------------------------------------------------
# RTM.md
# ---------------------------------------------------------------------------
def extract_rtm():
    text = open(f"{ROOT}/RTM.md", encoding="utf-8").read()

    title_m = re.match(r'^# (.+)$', text, re.M)
    title = title_m.group(1).strip()

    baseline_doc_m = re.search(r'\*\*Baseline requirements document\*\*:\s*`([^`]+)`,\s*generated at commit\s*`([^`]+)`', text)
    head_m = re.search(r'\*\*Current HEAD scanned\*\*:\s*`([^`]+)`\s*\(`([^`]+)`\),\s*branch\s*`([^`]+)`', text)
    rebase_m = re.search(r'\*\*Rebase acknowledgment\*\*:\s*(.+)', text)
    scan_date_m = re.search(r'\*\*Scan date\*\*:\s*(.+)', text)
    coverage_rule_m = re.search(
        r'## Coverage rule \(strict, as specified\)\s*\n\n(.*?)\n\n(\*\*Holmgang.*?\n)\n## Summary Table',
        text, re.S)

    metadata = {
        "baselineDocument": baseline_doc_m.group(1),
        "baselineCommit": baseline_doc_m.group(2),
        "currentHeadCommit": head_m.group(1),
        "currentHeadShort": head_m.group(2),
        "branch": head_m.group(3),
        "rebaseAcknowledgment": rebase_m.group(1).strip(),
        "scanDate": scan_date_m.group(1).strip(),
        "coverageRule": coverage_rule_m.group(1).strip(),
        "holmgangSurfaceNote": coverage_rule_m.group(2).strip(),
    }

    detail_start = text.index("## Detailed Requirements")
    gaps_start = text.index("## Coverage Gaps")
    detail_body = text[detail_start:gaps_start]

    module_re = re.compile(r'^### (.+)$', re.M)
    mods = list(module_re.finditer(detail_body))
    entry_re = re.compile(r'^#### (GIMLE-\d+) — (.+?)\s*$(.*?)(?=^#### GIMLE-|\Z)', re.M | re.S)

    requirements = []
    for i, mm in enumerate(mods):
        module = mm.group(1).strip()
        block_start = mm.end()
        block_end = mods[i + 1].start() if i + 1 < len(mods) else len(detail_body)
        module_block = detail_body[block_start:block_end]

        for em in entry_re.finditer(module_block):
            gid, feature, body = em.group(1), em.group(2).strip(), em.group(3)

            category = field(body, "Category") or ""
            status_raw = field(body, "Status") or ""
            coverage = field(body, "Coverage") or ""
            other_test_coverage = field(body, "Other test coverage (non-Holmgang, informational only)") or ""
            source_raw = field(body, "Source location(s)")

            status_note_m = re.search(r'_\((.*?)\)_\s*$', status_raw)
            status = re.sub(r'\s*_\(.*?\)_\s*$', '', status_raw).strip()
            status_note = status_note_m.group(1).strip() if status_note_m else ""

            holmgang_scenarios = []
            gap_note = ""
            if coverage == "Covered":
                sm = re.search(
                    r'-\s+\*\*Holmgang feature file\(s\) \+ scenario\(s\)\*\*:\s*\n(.*?)(?=\n-\s+\*\*|\Z)',
                    body, re.S)
                if sm:
                    section = sm.group(1)
                    scenarios = re.findall(
                        r'^\s*-\s*`([^`]+)`\s*—\s*Scenario:\s*\*(.+?)\*\s*$', section, re.M)
                    whys = re.findall(r'^\s*-\s*_Why this counts_:\s*(.+?)\s*$', section, re.M)
                    for idx, (feat_file, scenario_name) in enumerate(scenarios):
                        holmgang_scenarios.append(
                            {
                                "featureFile": feat_file,
                                "scenario": scenario_name,
                                "whyThisCounts": whys[idx] if idx < len(whys) else "",
                            }
                        )
            else:
                gap_note = field(body, "Gap note") or ""

            requirements.append(
                {
                    "id": gid,
                    "module": module,
                    "feature": feature,
                    "category": category,
                    "status": status,
                    "statusNote": status_note,
                    "coverage": coverage,
                    "holmgangScenarios": holmgang_scenarios,
                    "gapNote": gap_note,
                    "otherTestCoverage": other_test_coverage,
                    "sourceLocations": split_locations(source_raw),
                }
            )

    ids = [r["id"] for r in requirements]
    assert len(ids) == len(set(ids)), "duplicate IDs found"

    doc = {
        "title": title,
        "metadata": metadata,
        "requirementCount": len(requirements),
        "requirements": requirements,
    }
    return doc


if __name__ == "__main__":
    rm = extract_requirements_matrix()
    with open(f"{ROOT}/requirements-matrix.json", "w", encoding="utf-8") as f:
        json.dump(rm, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"requirements-matrix.json: {rm['requirementCount']} requirements")

    rtm = extract_rtm()
    with open(f"{ROOT}/rtm.json", "w", encoding="utf-8") as f:
        json.dump(rtm, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"rtm.json: {rtm['requirementCount']} requirements")
