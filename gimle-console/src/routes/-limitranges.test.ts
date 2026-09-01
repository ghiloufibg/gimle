import { describe, expect, it } from "vitest";
import {
  BOUND_KEYS,
  buildLimitRange,
  DEFAULT_LIMIT_RANGE_FORM,
  formatBound,
  limitRangeToForm,
  validateLimitRangeForm,
  type LimitRangeFormState,
} from "./limitranges";

// Pure form/spec logic only -- this project's vitest config is deliberately node-environment, so
// the JSX half of this screen is exercised in a real browser instead, not here.

function form(overrides: Partial<LimitRangeFormState> = {}): LimitRangeFormState {
  return {
    ...DEFAULT_LIMIT_RANGE_FORM,
    tenantId: "acme",
    maxRequest: { memory: "2Gi", cpu: "2000m" },
    ...overrides,
  };
}

describe("formatBound", () => {
  it("prints an absent bound as unbounded, not as zero", () => {
    expect(formatBound(undefined)).toBe("—");
  });

  it("prints a zero bound as the real bound it is", () => {
    expect(formatBound({ memory: "0", cpu: "0" })).toBe("0 / 0");
  });

  it("prints memory and cpu together", () => {
    expect(formatBound({ memory: "512Mi", cpu: "500m" })).toBe("512Mi / 500m");
  });
});

describe("limitRangeToForm", () => {
  it("leaves an undeclared bound's fields blank", () => {
    const state = limitRangeToForm({
      tenantId: "beta",
      maxRequest: { memory: "1Gi", cpu: "1000m" },
    });
    expect(state.tenantId).toBe("beta");
    expect(state.maxRequest).toEqual({ memory: "1Gi", cpu: "1000m" });
    expect(state.minRequest).toEqual({ memory: "", cpu: "" });
    expect(state.minLimit).toEqual({ memory: "", cpu: "" });
    expect(state.maxLimit).toEqual({ memory: "", cpu: "" });
  });

  it("keeps a zero bound as zero rather than collapsing it to blank", () => {
    const state = limitRangeToForm({ tenantId: "beta", minRequest: { memory: "0", cpu: "0" } });
    expect(state.minRequest).toEqual({ memory: "0", cpu: "0" });
  });

  it("round-trips every bound back through buildLimitRange unchanged", () => {
    const range = {
      tenantId: "acme",
      minRequest: { memory: "64Mi", cpu: "50m" },
      maxRequest: { memory: "2Gi", cpu: "2000m" },
      minLimit: { memory: "64Mi", cpu: "50m" },
      maxLimit: { memory: "4Gi", cpu: "4000m" },
    };
    expect(buildLimitRange(limitRangeToForm(range))).toEqual(range);
  });
});

describe("validateLimitRangeForm", () => {
  it("accepts a form with a tenant and one complete bound", () => {
    expect(validateLimitRangeForm(form())).toBeNull();
  });

  it("requires a tenant", () => {
    expect(validateLimitRangeForm(form({ tenantId: "  " }))).toBe("Tenant is required");
  });

  it("rejects a half-filled bound, naming the bound", () => {
    expect(validateLimitRangeForm(form({ maxRequest: { memory: "2Gi", cpu: "" } }))).toContain(
      "Max request needs both memory and cpu",
    );
    expect(validateLimitRangeForm(form({ maxRequest: { memory: "", cpu: "2000m" } }))).toContain(
      "Max request needs both memory and cpu",
    );
  });

  it("rejects a form declaring no bound at all", () => {
    const problem = validateLimitRangeForm(form({ maxRequest: { memory: "", cpu: "" } }));
    expect(problem).toContain("at least one bound");
  });

  it("accepts a bound of zero -- blank is what means unbounded, not zero", () => {
    const state = form({
      maxRequest: { memory: "", cpu: "" },
      minRequest: { memory: "0", cpu: "0" },
    });
    expect(validateLimitRangeForm(state)).toBeNull();
  });
});

describe("buildLimitRange", () => {
  it("omits every blank bound's key entirely rather than sending zeroes", () => {
    const spec = buildLimitRange(form());
    expect(spec).toEqual({ tenantId: "acme", maxRequest: { memory: "2Gi", cpu: "2000m" } });
    expect(Object.keys(spec)).not.toContain("minRequest");
    expect(Object.keys(spec)).not.toContain("maxLimit");
  });

  it("keeps an explicit zero bound", () => {
    const spec = buildLimitRange(form({ minRequest: { memory: "0", cpu: "0" } }));
    expect(spec.minRequest).toEqual({ memory: "0", cpu: "0" });
  });

  it("trims the tenant id and every bound value", () => {
    const spec = buildLimitRange(
      form({ tenantId: " acme ", maxRequest: { memory: " 2Gi ", cpu: " 2000m " } }),
    );
    expect(spec.tenantId).toBe("acme");
    expect(spec.maxRequest).toEqual({ memory: "2Gi", cpu: "2000m" });
  });

  it("carries all four bounds when all four are declared", () => {
    const spec = buildLimitRange(
      form({
        minRequest: { memory: "64Mi", cpu: "50m" },
        minLimit: { memory: "64Mi", cpu: "50m" },
        maxLimit: { memory: "4Gi", cpu: "4000m" },
      }),
    );
    for (const key of BOUND_KEYS) {
      expect(spec[key]).toBeDefined();
    }
  });
});
