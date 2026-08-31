import type { ControllerRevision, DaemonSet, DaemonSetSpecInput, Page } from "@/types";
import {
  addDaemonSet,
  daemonSets,
  listControllerRevisions,
  removeDaemonSet,
  rollbackControllerRevision,
} from "./fixture";
import { delay, paginate } from "./util";

export interface DaemonSetsRepository {
  fetchPage(args: { cursor: string | null; pageSize: number }): Promise<Page<DaemonSet>>;
  fetchOne(name: string): Promise<DaemonSet>;
  create(spec: DaemonSetSpecInput): Promise<DaemonSet>;
  remove(name: string, tenantId?: string | null): Promise<void>;
  fetchRevisions(name: string, tenantId?: string | null): Promise<ControllerRevision[]>;
  rollback(
    name: string,
    toRevision?: number,
    tenantId?: string | null,
  ): Promise<ControllerRevision>;
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
  async fetchRevisions(name: string, _tenantId?: string | null) {
    const d = daemonSets.find((x) => x.spec.name === name);
    if (!d) throw new Error(`DaemonSet not found: ${name}`);
    return delay(listControllerRevisions("daemonset", name, d.spec.moduleId, d.spec.artifactPath));
  }
  async rollback(name: string, toRevision?: number, _tenantId?: string | null) {
    const d = daemonSets.find((x) => x.spec.name === name);
    if (!d) throw new Error(`DaemonSet not found: ${name}`);
    const rev = rollbackControllerRevision(
      "daemonset",
      name,
      d.spec.moduleId,
      d.spec.artifactPath,
      toRevision,
    );
    d.spec.moduleId = rev.moduleId;
    d.spec.artifactPath = rev.artifactPath;
    return delay(rev);
  }
}
