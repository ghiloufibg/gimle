import { describe, expect, it } from "vitest";
import { buildJobSpec, DEFAULT_JOB_FORM, jobFormIsValid, type JobFormState } from "./jobs.new";

// Pure form-validation/spec-building logic only -- this project's vitest config is deliberately
// node-environment (see vitest.config.ts); the JSX half of this screen is exercised live in a
// real browser instead, not here.

function form(overrides: Partial<JobFormState> = {}): JobFormState {
  return {
    ...DEFAULT_JOB_FORM,
    name: "nightly-cleanup",
    moduleName: "cleanup-job",
    moduleVersion: "1.0.0",
    ...overrides,
  };
}

describe("jobFormIsValid", () => {
  it("requires name, moduleName, and moduleVersion", () => {
    expect(jobFormIsValid(form())).toBe(true);
    expect(jobFormIsValid(form({ name: "" }))).toBe(false);
    expect(jobFormIsValid(form({ moduleName: "" }))).toBe(false);
    expect(jobFormIsValid(form({ moduleVersion: "" }))).toBe(false);
  });

  it("does not require artifactPath, backoffLimit, or activeDeadlineSeconds", () => {
    expect(jobFormIsValid(form({ artifactPath: "", activeDeadlineSeconds: "" }))).toBe(true);
  });
});

describe("buildJobSpec", () => {
  it("builds a minimal spec, defaulting tenantId to null and omitting activeDeadlineSeconds", () => {
    const spec = buildJobSpec(form());
    expect(spec).toEqual({
      name: "nightly-cleanup",
      moduleId: { name: "cleanup-job", version: "1.0.0" },
      artifactPath: "",
      backoffLimit: 6,
      tenantId: null,
    });
  });

  it("includes activeDeadlineSeconds only when present", () => {
    const spec = buildJobSpec(form({ activeDeadlineSeconds: "600" }));
    expect(spec.activeDeadlineSeconds).toBe(600);
  });

  it("resolves a selected tenant, treating the NONE sentinel as null", () => {
    expect(buildJobSpec(form({ tenantId: "tenant-1" })).tenantId).toBe("tenant-1");
    expect(buildJobSpec(form({ tenantId: "NONE" })).tenantId).toBeNull();
  });

  it("clamps a negative or non-numeric backoffLimit to zero", () => {
    expect(buildJobSpec(form({ backoffLimit: "-3" })).backoffLimit).toBe(0);
    expect(buildJobSpec(form({ backoffLimit: "abc" })).backoffLimit).toBe(0);
  });
});
