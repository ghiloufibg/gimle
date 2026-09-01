import type { InstanceEvent } from "@/types";
import { delay } from "./util";

/**
 * The store keys an instance's timeline by the exact `(tenantId, deploymentName, instanceIndex)`
 * triple, never a bare-name search across tenants -- so an omitted `tenantId` addresses only the
 * untenanted namespace rather than every tenant's instance of that name.
 */
export interface EventsRepository {
  fetchForInstance(
    deploymentName: string,
    instanceIndex: number,
    tenantId?: string | null,
  ): Promise<InstanceEvent[]>;
}

const mockEvents: InstanceEvent[] = [
  {
    id: "evt-4",
    deploymentName: "greeter-consumer",
    instanceIndex: 0,
    kind: "ACTIVE",
    message: "instance active",
    occurredAtEpochMilli: 1_760_000_040_000,
  },
  {
    id: "evt-3",
    deploymentName: "greeter-consumer",
    instanceIndex: 0,
    kind: "STARTING",
    message: "starting instance",
    occurredAtEpochMilli: 1_760_000_030_000,
  },
  {
    id: "evt-2",
    deploymentName: "greeter-consumer",
    instanceIndex: 0,
    kind: "TRANSITION_FAILED",
    message: "could not resolve module artifact",
    causeSummary: "GimleResolutionException: no artifact for greeter-consumer@1.0.0",
    occurredAtEpochMilli: 1_760_000_020_000,
  },
  {
    id: "evt-1",
    deploymentName: "greeter-consumer",
    instanceIndex: 0,
    kind: "INSTALLED",
    message: "installed module",
    occurredAtEpochMilli: 1_760_000_010_000,
  },
];

export class MockEventsRepository implements EventsRepository {
  async fetchForInstance(
    deploymentName: string,
    instanceIndex: number,
    _tenantId?: string | null,
  ): Promise<InstanceEvent[]> {
    return delay(
      mockEvents
        .filter((e) => e.deploymentName === deploymentName && e.instanceIndex === instanceIndex)
        .map((e) => ({ ...e })),
    );
  }
}
