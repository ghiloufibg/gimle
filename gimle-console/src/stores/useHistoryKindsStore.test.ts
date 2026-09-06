import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  metricsHistoryRepo: { fetchProcessKinds: vi.fn() },
  tracesRepo: { fetchProcessKinds: vi.fn() },
}));

import { metricsHistoryRepo, tracesRepo } from "@/repositories";
import { ALL_PROCESS_KINDS, useHistoryKindsStore } from "./useHistoryKindsStore";

const metricsMock = vi.mocked(metricsHistoryRepo.fetchProcessKinds);
const tracesMock = vi.mocked(tracesRepo.fetchProcessKinds);

beforeEach(() => {
  vi.clearAllMocks();
  useHistoryKindsStore.setState({ metrics: null, traces: null });
});

describe("useHistoryKindsStore", () => {
  it("keeps the two signals' answers apart", async () => {
    metricsMock.mockResolvedValue(["AGENT", "ANDVARI", "CONTROLPLANE", "WORKER"]);
    tracesMock.mockResolvedValue(["CONTROLPLANE", "WORKER"]);

    await useHistoryKindsStore.getState().load("metrics");
    await useHistoryKindsStore.getState().load("traces");

    expect(useHistoryKindsStore.getState().kindsFor("metrics")).toEqual([
      "AGENT",
      "ANDVARI",
      "CONTROLPLANE",
      "WORKER",
    ]);
    expect(useHistoryKindsStore.getState().kindsFor("traces")).toEqual(["CONTROLPLANE", "WORKER"]);
  });

  it("asks each signal's endpoint once", async () => {
    metricsMock.mockResolvedValue(["CONTROLPLANE"]);

    await useHistoryKindsStore.getState().load("metrics");
    await useHistoryKindsStore.getState().load("metrics");

    expect(metricsMock).toHaveBeenCalledTimes(1);
    expect(tracesMock).not.toHaveBeenCalled();
  });

  it("offers every known kind while unanswered", () => {
    expect(useHistoryKindsStore.getState().kindsFor("traces")).toEqual(ALL_PROCESS_KINDS);
  });

  it("keeps a usable picker when the read fails or names nothing", async () => {
    metricsMock.mockRejectedValue(new Error("unreachable"));
    tracesMock.mockResolvedValue([]);

    await useHistoryKindsStore.getState().load("metrics");
    await useHistoryKindsStore.getState().load("traces");

    expect(useHistoryKindsStore.getState().kindsFor("metrics")).toEqual(ALL_PROCESS_KINDS);
    expect(useHistoryKindsStore.getState().kindsFor("traces")).toEqual(ALL_PROCESS_KINDS);
  });
});
