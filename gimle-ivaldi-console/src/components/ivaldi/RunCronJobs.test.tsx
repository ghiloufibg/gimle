// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import type { RunCronJob } from "@/repositories";

import { RunCronJobs } from "./RunCronJobs";

afterEach(cleanup);

describe("RunCronJobs", () => {
  it("lists each cronjob's own generated Jobs, newest first as the backend already ordered them", () => {
    const cronJobs: RunCronJob[] = [
      {
        name: "nightly",
        jobs: [
          { name: "nightly-1700086400", phase: "FAILED", firingTime: "2023-11-15T22:13:20Z" },
          { name: "nightly-1700000000", phase: "SUCCEEDED", firingTime: "2023-11-14T22:13:20Z" },
        ],
      },
    ];

    render(<RunCronJobs cronJobs={cronJobs} />);

    expect(screen.getByText("nightly")).toBeTruthy();
    expect(screen.getByText("FAILED")).toBeTruthy();
    expect(screen.getByText("SUCCEEDED")).toBeTruthy();
    expect(screen.queryByText("No firings yet.")).toBeNull();
  });

  it("shows a cronjob with no firings yet rather than hiding it", () => {
    render(<RunCronJobs cronJobs={[{ name: "nightly", jobs: [] }]} />);

    expect(screen.getByText("nightly")).toBeTruthy();
    expect(screen.getByText("No firings yet.")).toBeTruthy();
  });

  it("reports no CronJobs in the blueprint when the list is empty", () => {
    render(<RunCronJobs cronJobs={[]} />);

    expect(screen.getByText("No CronJobs in this blueprint.")).toBeTruthy();
  });
});
