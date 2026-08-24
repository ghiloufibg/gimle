import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpMetricsHistoryRepository } from "./metricsHistory";
import { HttpTracesHistoryRepository } from "./tracesHistory";
import { ApiError } from "./apiClient";
import { resetHistoryBackendAvailability } from "./historyAvailability";
import { stubFetchSequence, textResponse } from "./testUtil";
import type { ProcessTarget } from "@/types";

afterEach(() => {
  vi.unstubAllGlobals();
  resetHistoryBackendAvailability();
});

const TARGET: ProcessTarget = { processKind: "CONTROLPLANE", processId: "controlplane" };

describe("history backend 404 memory shared across metrics and traces", () => {
  it("a 404 discovered by the metrics repository stops the traces repository from fetching too", async () => {
    const fetchMock = stubFetchSequence([() => textResponse("no muninn endpoint configured", 404)]);
    const metricsRepo = new HttpMetricsHistoryRepository();
    const tracesRepo = new HttpTracesHistoryRepository();

    await expect(
      metricsRepo.fetchPage({ target: TARGET, cursor: null, limit: 120 }),
    ).rejects.toMatchObject(new ApiError(404, "no muninn endpoint configured"));
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await expect(
      tracesRepo.fetchPage({ target: TARGET, cursor: null, limit: 60 }),
    ).rejects.toMatchObject(new ApiError(404, "no muninn endpoint configured"));
    // The traces repository's first-ever call never touched the network -- the metrics
    // repository's earlier 404 already answered the question for this session.
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("a 404 discovered by the traces repository stops the metrics repository from fetching too", async () => {
    const fetchMock = stubFetchSequence([() => textResponse("no muninn endpoint configured", 404)]);
    const tracesRepo = new HttpTracesHistoryRepository();
    const metricsRepo = new HttpMetricsHistoryRepository();

    await expect(
      tracesRepo.fetchPage({ target: TARGET, cursor: null, limit: 60 }),
    ).rejects.toMatchObject(new ApiError(404, "no muninn endpoint configured"));
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await expect(
      metricsRepo.fetchPage({ target: TARGET, cursor: null, limit: 120 }),
    ).rejects.toMatchObject(new ApiError(404, "no muninn endpoint configured"));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("a non-404 failure (e.g. a transient 500) is not remembered -- the next call still fetches", async () => {
    const fetchMock = stubFetchSequence([
      () => textResponse("internal error", 500),
      () => textResponse("no muninn endpoint configured", 404),
    ]);
    const repo = new HttpMetricsHistoryRepository();

    await expect(
      repo.fetchPage({ target: TARGET, cursor: null, limit: 120 }),
    ).rejects.toMatchObject(new ApiError(500, "internal error"));
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await expect(
      repo.fetchPage({ target: TARGET, cursor: null, limit: 120 }),
    ).rejects.toMatchObject(new ApiError(404, "no muninn endpoint configured"));
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
