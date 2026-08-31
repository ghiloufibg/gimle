import type { ControllerRevision, Page, StatefulSet, StatefulSetSpecInput } from "@/types";
import {
  addStatefulSet,
  listControllerRevisions,
  removeStatefulSet,
  rollbackControllerRevision,
  statefulSets,
} from "./fixture";
import { delay, paginate } from "./util";

export interface StatefulSetsRepository {
  fetchPage(args: { cursor: string | null; pageSize: number }): Promise<Page<StatefulSet>>;
  fetchOne(name: string): Promise<StatefulSet>;
  create(spec: StatefulSetSpecInput): Promise<StatefulSet>;
  remove(name: string, tenantId?: string | null): Promise<void>;
  fetchRevisions(name: string, tenantId?: string | null): Promise<ControllerRevision[]>;
  rollback(
    name: string,
    toRevision?: number,
    tenantId?: string | null,
  ): Promise<ControllerRevision>;
}

export class MockStatefulSetsRepository implements StatefulSetsRepository {
  async fetchPage({ cursor, pageSize }: { cursor: string | null; pageSize: number }) {
    return delay(paginate(statefulSets, cursor, pageSize));
  }
  async fetchOne(name: string) {
    const s = statefulSets.find((x) => x.spec.name === name);
    if (!s) throw new Error(`StatefulSet not found: ${name}`);
    return delay(s);
  }
  async create(spec: StatefulSetSpecInput) {
    const s: StatefulSet = { spec, instances: [], unplacedCount: spec.replicas };
    addStatefulSet(s);
    return delay(s);
  }
  async remove(name: string, _tenantId?: string | null) {
    // Fixture data is keyed by bare name only -- tenantId is accepted for interface parity with
    // HttpStatefulSetsRepository but unused here.
    removeStatefulSet(name);
    return delay(undefined);
  }
  async fetchRevisions(name: string, _tenantId?: string | null) {
    const s = statefulSets.find((x) => x.spec.name === name);
    if (!s) throw new Error(`StatefulSet not found: ${name}`);
    return delay(
      listControllerRevisions("statefulset", name, s.spec.moduleId, s.spec.artifactPath),
    );
  }
  async rollback(name: string, toRevision?: number, _tenantId?: string | null) {
    const s = statefulSets.find((x) => x.spec.name === name);
    if (!s) throw new Error(`StatefulSet not found: ${name}`);
    const rev = rollbackControllerRevision(
      "statefulset",
      name,
      s.spec.moduleId,
      s.spec.artifactPath,
      toRevision,
    );
    s.spec.moduleId = rev.moduleId;
    s.spec.artifactPath = rev.artifactPath;
    return delay(rev);
  }
}
