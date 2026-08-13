import type { Page, StatefulSet, StatefulSetSpecInput } from "@/types";
import { addStatefulSet, removeStatefulSet, statefulSets } from "./fixture";
import { delay, paginate } from "./util";

export interface StatefulSetsRepository {
  fetchPage(args: { cursor: string | null; pageSize: number }): Promise<Page<StatefulSet>>;
  fetchOne(name: string): Promise<StatefulSet>;
  create(spec: StatefulSetSpecInput): Promise<StatefulSet>;
  remove(name: string): Promise<void>;
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
  async remove(name: string) {
    removeStatefulSet(name);
    return delay(undefined);
  }
}
