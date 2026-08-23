export type CoverageTier = "e2e" | "unit" | "none";
export type Confidence = "High" | "Medium" | "Low";
export type RtmStatus = "Active" | "New" | "Modified" | "Removed";

export interface HolmgangScenario {
  file: string;
  scenario: string;
  why: string;
}

export interface Requirement {
  id: string;
  module: string;
  category: string;
  feature: string;
  story: string;
  status: string;
  statusDetail: string;
  confidence: Confidence;
  unitTests: string;
  src: string[];
  gherkin: string[];
  rtmStatus: RtmStatus;
  rtmNote: string;
  tier: CoverageTier;
  coverage: string;
  scenarios: HolmgangScenario[];
  gapNote: string;
  uatStep: string;
  automated: boolean;
  signedOff: boolean;
  signedOffBy: string | null;
  signedOffAt: string | null;
}

export interface ModuleCoverage {
  e2e: number;
  unit: number;
  none: number;
  total: number;
}

export interface GimleData {
  dataDir: string;
  loadedAt: string;
  rtmMeta: Record<string, unknown>;
  summary: {
    total: number;
    coveredE2E: number;
    signedOff: number;
    moduleCount: number;
  };
  byModule: Record<string, ModuleCoverage>;
  requirements: Requirement[];
}
