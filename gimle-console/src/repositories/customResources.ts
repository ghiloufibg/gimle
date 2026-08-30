import type { CustomResourceItem, KindDefinitionSummary } from "@/types";
import { delay } from "./util";

/**
 * The custom-kinds read surface: the kind catalog first, then one kind's full instance set on
 * demand -- the same two-call shape the CLI's own table rendering uses. Deliberately read-only:
 * authoring KindDefinitions and instances stays in the CLI, where apply semantics (schema
 * validation errors, generation CAS retries) actually live.
 */
export interface CustomResourcesRepository {
  fetchKinds(): Promise<KindDefinitionSummary[]>;
  fetchResources(kindName: string): Promise<CustomResourceItem[]>;
}

const mockGreetingDefinition: KindDefinitionSummary = {
  kindName: "custom.Greeting",
  scope: "Tenant",
  description: "A greeting this cluster should keep saying",
  names: { plural: "greetings", shortNames: ["gr"] },
  schema: {
    fields: [
      { name: "message", type: "string", required: true },
      { name: "repeat", type: "int", default: 1, min: 1, max: 100 },
      { name: "tone", type: "enum", values: ["friendly", "formal"], default: "friendly" },
    ],
  },
  printColumns: [
    { name: "MESSAGE", path: "spec.message" },
    { name: "SAID", path: "status.timesSaid" },
  ],
  generation: 1,
};

const mockAlertRuleDefinition: KindDefinitionSummary = {
  kindName: "acme.AlertRule",
  scope: "Cluster",
  description: "A threshold alert evaluated by the acme alerting operator",
  names: { plural: "alertrules", shortNames: [] },
  schema: {
    fields: [
      { name: "metric", type: "string", required: true },
      { name: "threshold", type: "double", required: true },
      { name: "enabled", type: "bool", default: true },
    ],
  },
  printColumns: [{ name: "METRIC", path: "spec.metric" }],
  generation: 3,
};

const mockResources: Record<string, CustomResourceItem[]> = {
  "custom.Greeting": [
    {
      kind: "custom.Greeting",
      name: "hello-world",
      tenantId: "team-a",
      generation: 2,
      spec: { message: "hello", repeat: 5, tone: "friendly" },
      status: { timesSaid: 5, observedGeneration: 2 },
    },
    {
      kind: "custom.Greeting",
      name: "welcome-banner",
      tenantId: "team-a",
      generation: 3,
      spec: { message: "welcome", repeat: 1, tone: "formal" },
      // observedGeneration deliberately behind: the "operator has not caught up" rendering case.
      status: { timesSaid: 1, observedGeneration: 2 },
    },
    {
      kind: "custom.Greeting",
      name: "goodbye",
      tenantId: "team-b",
      generation: 1,
      spec: { message: "goodbye", repeat: 2, tone: "friendly" },
      // No operator has reported yet: status stays null, never a fabricated empty object.
      status: null,
    },
  ],
  "acme.AlertRule": [
    {
      kind: "acme.AlertRule",
      name: "high-error-rate",
      generation: 1,
      spec: { metric: "gimle.module.error.rate", threshold: 0.05, enabled: true },
      status: { firing: false, observedGeneration: 1 },
    },
  ],
};

export class MockCustomResourcesRepository implements CustomResourcesRepository {
  async fetchKinds(): Promise<KindDefinitionSummary[]> {
    return delay([mockAlertRuleDefinition, mockGreetingDefinition]);
  }

  async fetchResources(kindName: string): Promise<CustomResourceItem[]> {
    const resources = mockResources[kindName];
    if (!resources) throw new Error(`no such kind: ${kindName}`);
    return delay(resources.map((r) => ({ ...r })));
  }
}
