import { describe, expect, it } from "vitest";
import {
  applyLogFilterParams,
  describeLogFilter,
  EMPTY_LOG_FILTER,
  isLogFilterActive,
  LOG_LEVELS,
  logFilterKey,
  matchesLogFilter,
  toLogFilter,
} from "./log-filter";
import type { LogLine } from "@/types";

function structured(level: string, message: string): LogLine {
  return {
    timestamp: "2026-08-10T10:00:00Z",
    level: level as "INFO",
    logger: "com.example.Handler",
    thread: "worker-3",
    message,
    category: "PLATFORM",
    processRole: "NODE",
    nodeId: "node-a",
  };
}

const RAW: LogLine = {
  timestamp: "2026-08-10T10:00:00Z",
  category: "SYSTEM",
  raw: "dmesg: cgroup limit reached for slice gimle.slice",
};

describe("matchesLogFilter", () => {
  it("keeps every line when no constraint is set", () => {
    expect(matchesLogFilter(structured("TRACE", "x"), EMPTY_LOG_FILTER)).toBe(true);
    expect(matchesLogFilter(RAW, EMPTY_LOG_FILTER)).toBe(true);
  });

  it("treats a level as a threshold, keeping that level and everything above it", () => {
    const warn = toLogFilter("WARN", null);
    expect(matchesLogFilter(structured("WARN", "x"), warn)).toBe(true);
    expect(matchesLogFilter(structured("ERROR", "x"), warn)).toBe(true);
  });

  it("drops every level below the threshold", () => {
    const warn = toLogFilter("WARN", null);
    for (const level of ["TRACE", "DEBUG", "INFO"]) {
      expect(matchesLogFilter(structured(level, "x"), warn)).toBe(false);
    }
  });

  it("never keeps an unrankable raw capture line under a level threshold", () => {
    // Not even at the lowest threshold: a line with no level cannot be placed on the scale at
    // all, which is exactly what the backend's own filter decides.
    expect(matchesLogFilter(RAW, toLogFilter("TRACE", null))).toBe(false);
  });

  it("matches text case-insensitively against message, logger and raw text", () => {
    expect(matchesLogFilter(structured("INFO", "call TIMED out"), toLogFilter(null, "timed"))).toBe(
      true,
    );
    expect(matchesLogFilter(structured("INFO", "ok"), toLogFilter(null, "example.Handler"))).toBe(
      true,
    );
    expect(matchesLogFilter(RAW, toLogFilter(null, "CGROUP"))).toBe(true);
    expect(matchesLogFilter(structured("INFO", "ok"), toLogFilter(null, "absent"))).toBe(false);
  });

  it("ignores machine identifier fields, so a query cannot match a whole node at once", () => {
    expect(matchesLogFilter(structured("INFO", "ok"), toLogFilter(null, "node-a"))).toBe(false);
    expect(matchesLogFilter(structured("INFO", "ok"), toLogFilter(null, "worker-3"))).toBe(false);
  });

  it("treats the text as a literal substring, not a regular expression", () => {
    expect(matchesLogFilter(structured("INFO", "a.b"), toLogFilter(null, "a.b"))).toBe(true);
    expect(matchesLogFilter(structured("INFO", "axb"), toLogFilter(null, "a.b"))).toBe(false);
  });

  it("requires both constraints to hold when both are set", () => {
    const both = toLogFilter("WARN", "timed out");
    expect(matchesLogFilter(structured("ERROR", "call timed out"), both)).toBe(true);
    expect(matchesLogFilter(structured("INFO", "call timed out"), both)).toBe(false);
    expect(matchesLogFilter(structured("ERROR", "quota violation"), both)).toBe(false);
  });
});

describe("toLogFilter", () => {
  it("normalizes absent, blank and unknown values to 'no constraint'", () => {
    expect(toLogFilter(null, null)).toEqual(EMPTY_LOG_FILTER);
    expect(toLogFilter(undefined, "   ")).toEqual(EMPTY_LOG_FILTER);
    expect(toLogFilter("SEVERE", null)).toEqual(EMPTY_LOG_FILTER);
  });

  it("keeps a recognized level and non-blank text", () => {
    expect(toLogFilter("ERROR", "boom")).toEqual({ minLevel: "ERROR", contains: "boom" });
  });
});

describe("applyLogFilterParams", () => {
  it("omits both parameters entirely when nothing is filtered", () => {
    const params = new URLSearchParams();
    applyLogFilterParams(params, EMPTY_LOG_FILTER);
    expect(params.toString()).toBe("");
  });

  it("sets the two parameter names the backend routes accept", () => {
    const params = new URLSearchParams();
    applyLogFilterParams(params, toLogFilter("WARN", "timed out"));
    expect(params.get("level")).toBe("WARN");
    expect(params.get("contains")).toBe("timed out");
  });
});

describe("isLogFilterActive / describeLogFilter / logFilterKey", () => {
  it("reports an empty filter as inactive", () => {
    expect(isLogFilterActive(EMPTY_LOG_FILTER)).toBe(false);
    expect(isLogFilterActive(toLogFilter("INFO", null))).toBe(true);
    expect(isLogFilterActive(toLogFilter(null, "x"))).toBe(true);
  });

  it("describes only the constraints actually set", () => {
    expect(describeLogFilter(toLogFilter("WARN", null))).toBe("level ≥ WARN");
    expect(describeLogFilter(toLogFilter(null, "boom"))).toBe("containing “boom”");
    expect(describeLogFilter(toLogFilter("ERROR", "boom"))).toBe(
      "level ≥ ERROR, containing “boom”",
    );
  });

  it("gives distinct filters distinct keys, so each gets its own log-tail store", () => {
    expect(logFilterKey(toLogFilter("WARN", null))).not.toBe(logFilterKey(EMPTY_LOG_FILTER));
    expect(logFilterKey(toLogFilter("WARN", "a"))).not.toBe(logFilterKey(toLogFilter("WARN", "b")));
    expect(logFilterKey(toLogFilter("WARN", "a"))).toBe(logFilterKey(toLogFilter("WARN", "a")));
  });

  it("declares the levels lowest-to-highest, which is what makes the threshold work", () => {
    expect(LOG_LEVELS).toEqual(["TRACE", "DEBUG", "INFO", "WARN", "ERROR"]);
  });
});
