import type { DaemonSet, DaemonSetSpecInput, Page } from "@/types";
import { addDaemonSet, daemonSets, removeDaemonSet } from "./fixture";
import { delay, paginate } from "./util";

export interface DaemonSetsRepository {
  fetchPage(args: { cursor: string | null; pageSize: number }): Promise<Page<DaemonSet>>;
  fetchOne(name: string): Promise<DaemonSet>;
  create(spec: DaemonSetSpecInput): Promise<DaemonSet>;
  remove(name: string, tenantId?: string | null): Promise<void>;
}

export class MockDaemonSetsRepository implements DaemonSetsRepository {
  async fetchPage({ cursor, pageSize }: { cursor: string | null; pageSize: number }) {
    return delay(paginate(daemonSets, cursor, pageSize));
  }
  async fetchOne(name: string) {
    const d = daemonSets.find((x) => x.spec.name === name);
    if (!d) throw new Error(`DaemonSet not found: ${name}`);
    return delay(d);
  }
  async create(spec: DaemonSetSpecInput) {
    const d: DaemonSet = { spec, instances: [] };
    addDaemonSet(d);
    return delay(d);
  }
  async remove(name: string, _tenantId?: string | null) {
    // Fixture data is keyed by bare name only -- tenantId is accepted for interface parity with
    // HttpDaemonSetsRepository but unused here.
    removeDaemonSet(name);
    return delay(undefined);
  }
}
