import type { LimitRange } from "@/types";
import { delay } from "./util";

export interface LimitRangesRepository {
  fetchAll(): Promise<LimitRange[]>;
  fetchOne(tenantId: string): Promise<LimitRange>;
  save(spec: LimitRange): Promise<void>;
  remove(tenantId: string): Promise<void>;
}

const mockLimitRanges: LimitRange[] = [
  {
    tenantId: "acme",
    minRequest: { memory: "64Mi", cpu: "50m" },
    maxRequest: { memory: "2Gi", cpu: "2000m" },
    maxLimit: { memory: "4Gi", cpu: "4000m" },
  },
  {
    // Only one bound declared -- an absent bound is unbounded, not zero.
    tenantId: "beta",
    maxRequest: { memory: "512Mi", cpu: "500m" },
  },
];

export class MockLimitRangesRepository implements LimitRangesRepository {
  async fetchAll(): Promise<LimitRange[]> {
    return delay(mockLimitRanges.map((r) => ({ ...r })));
  }

  async fetchOne(tenantId: string): Promise<LimitRange> {
    const found = mockLimitRanges.find((r) => r.tenantId === tenantId);
    if (!found) throw new Error(`Limit range not found: ${tenantId}`);
    return delay({ ...found });
  }

  async save(spec: LimitRange): Promise<void> {
    const i = mockLimitRanges.findIndex((r) => r.tenantId === spec.tenantId);
    if (i >= 0) mockLimitRanges[i] = spec;
    else mockLimitRanges.push(spec);
    return delay(undefined);
  }

  async remove(tenantId: string): Promise<void> {
    const i = mockLimitRanges.findIndex((r) => r.tenantId === tenantId);
    if (i >= 0) mockLimitRanges.splice(i, 1);
    return delay(undefined);
  }
}
