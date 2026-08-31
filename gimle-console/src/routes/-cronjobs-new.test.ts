import { describe, expect, it } from "vitest";
import {
  buildCronJobSpec,
  cronJobFormIsValid,
  DEFAULT_CRONJOB_FORM,
  isValidCronSchedule,
  type CronJobFormState,
} from "./cronjobs.new";

// Pure form-validation/spec-building logic only -- this project's vitest config is deliberately
// node-environment (see vitest.config.ts); the JSX half of this screen is exercised live in a
// real browser instead, not here.

function form(overrides: Partial<CronJobFormState> = {}): CronJobFormState {
  return {
    ...DEFAULT_CRONJOB_FORM,
    name: "nightly-report",
    schedule: "0 2 * * *",
    moduleName: "report-job",
    moduleVersion: "1.0.0",
    ...overrides,
  };
}

describe("isValidCronSchedule", () => {
  it("accepts exactly five whitespace-separated fields", () => {
    expect(isValidCronSchedule("0 2 * * *")).toBe(true);
    expect(isValidCronSchedule("  0   2  *  *  * ")).toBe(true);
  });

  it("rejects too few or too many fields", () => {
    expect(isValidCronSchedule("0 2 * *")).toBe(false);
    expect(isValidCronSchedule("0 2 * * * *")).toBe(false);
    expect(isValidCronSchedule("")).toBe(false);
  });
});

describe("cronJobFormIsValid", () => {
  it("requires name, a valid schedule, moduleName, and moduleVersion", () => {
    expect(cronJobFormIsValid(form())).toBe(true);
    expect(cronJobFormIsValid(form({ name: "" }))).toBe(false);
    expect(cronJobFormIsValid(form({ schedule: "0 2 * *" }))).toBe(false);
    expect(cronJobFormIsValid(form({ moduleName: "" }))).toBe(false);
    expect(cronJobFormIsValid(form({ moduleVersion: "" }))).toBe(false);
  });
});

describe("buildCronJobSpec", () => {
  it("builds a minimal spec with a nested jobTemplate, defaulting tenantId to null", () => {
    const spec = buildCronJobSpec(form());
    expect(spec).toEqual({
      name: "nightly-report",
      schedule: "0 2 * * *",
      jobTemplate: {
        moduleId: { name: "report-job", version: "1.0.0" },
        artifactPath: "",
        backoffLimit: 6,
      },
      concurrencyPolicy: "ALLOW",
      tenantId: null,
    });
  });

  it("trims the schedule and includes optional deadlines only when present", () => {
    const spec = buildCronJobSpec(
      form({
        schedule: "  0 2 * * *  ",
        activeDeadlineSeconds: "600",
        startingDeadlineSeconds: "120",
      }),
    );
    expect(spec.schedule).toBe("0 2 * * *");
    expect(spec.jobTemplate.activeDeadlineSeconds).toBe(600);
    expect(spec.startingDeadlineSeconds).toBe(120);
  });

  it("resolves a selected tenant, treating the NONE sentinel as null", () => {
    expect(buildCronJobSpec(form({ tenantId: "tenant-1" })).tenantId).toBe("tenant-1");
    expect(buildCronJobSpec(form({ tenantId: "NONE" })).tenantId).toBeNull();
  });
});
