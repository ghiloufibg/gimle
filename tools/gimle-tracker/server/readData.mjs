import { readFile } from "node:fs/promises";
import path from "node:path";

const FILES = {
  matrix: "requirements-matrix.json",
  rtm: "rtm.json",
  uat: "uat-checklist.json",
};

function stripTicks(value) {
  return typeof value === "string" ? value.replace(/`/g, "") : value;
}

async function readJson(dataDir, filename) {
  const fullPath = path.join(dataDir, filename);
  let raw;
  try {
    raw = await readFile(fullPath, "utf8");
  } catch (err) {
    throw new Error(`Could not read ${filename} in ${dataDir}: ${err.message}`);
  }
  try {
    return JSON.parse(raw);
  } catch (err) {
    throw new Error(`${filename} in ${dataDir} is not valid JSON: ${err.message}`);
  }
}

function mergeRequirement(a, b, c) {
  const tier =
    b.coverage === "Covered"
      ? "e2e"
      : a.testCoverageLevel && a.testCoverageLevel !== "None"
        ? "unit"
        : "none";

  return {
    id: a.id,
    module: a.module,
    category: a.category,
    feature: a.feature,
    story: a.userStory,
    status: a.statusSummary,
    statusDetail: a.statusDetail && a.statusDetail !== a.statusSummary ? stripTicks(a.statusDetail) : "",
    confidence: a.confidence,
    unitTests: stripTicks(a.testCoverageDetail),
    src: (a.sourceLocations || []).map(stripTicks),
    gherkin: a.gherkinSteps || [],
    rtmStatus: b.status || "Active",
    rtmNote: b.statusNote || "",
    tier,
    coverage: b.coverage || "Not Covered",
    scenarios: (b.holmgangScenarios || []).map((sc) => ({
      file: stripTicks(sc.featureFile || "").split("/").pop(),
      scenario: sc.scenario,
      why: sc.whyThisCounts,
    })),
    gapNote: stripTicks(b.gapNote || ""),
    uatStep: c.step || "",
    automated: !!c.automatedCoverage,
    signedOff: !!c.signedOff,
    signedOffBy: c.signedOffBy || null,
    signedOffAt: c.signedOffAt || null,
  };
}

/**
 * Reads the three RTM/requirements-matrix/UAT files from dataDir, fresh off disk on every
 * call (no caching - this is a read-only viewer, not a store), and merges them by requirement id.
 */
export async function loadGimleData(dataDir) {
  const [matrix, rtm, uat] = await Promise.all([
    readJson(dataDir, FILES.matrix),
    readJson(dataDir, FILES.rtm),
    readJson(dataDir, FILES.uat),
  ]);

  const rtmById = new Map((rtm.requirements || []).map((r) => [r.id, r]));
  const uatById = new Map((uat.items || []).map((i) => [i.id, i]));

  const requirements = (matrix.requirements || []).map((a) =>
    mergeRequirement(a, rtmById.get(a.id) || {}, uatById.get(a.id) || {}),
  );

  const byModule = {};
  for (const r of requirements) {
    byModule[r.module] ??= { e2e: 0, unit: 0, none: 0, total: 0 };
    byModule[r.module][r.tier] += 1;
    byModule[r.module].total += 1;
  }

  const coveredE2E = requirements.filter((r) => r.tier === "e2e").length;
  const signedOff = requirements.filter((r) => r.signedOff).length;

  return {
    dataDir,
    loadedAt: new Date().toISOString(),
    rtmMeta: rtm.metadata || {},
    summary: {
      total: requirements.length,
      coveredE2E,
      signedOff,
      moduleCount: Object.keys(byModule).length,
    },
    byModule,
    requirements,
  };
}
