import { describe, expect, it } from "vitest";

import { formatCpu, formatMemory, isValidCpu, isValidMemory, parseCpu, parseMemory } from "./units";

describe("parseMemory", () => {
  it("parses each unit suffix into bytes", () => {
    expect(parseMemory("512")).toBe(512);
    expect(parseMemory("64Ki")).toBe(64 * 1024);
    expect(parseMemory("256Mi")).toBe(256 * 1024 ** 2);
    expect(parseMemory("1Gi")).toBe(1024 ** 3);
    expect(parseMemory("2Ti")).toBe(2 * 1024 ** 4);
  });

  it("tolerates surrounding whitespace and fractional values", () => {
    expect(parseMemory(" 1.5Gi ")).toBe(1.5 * 1024 ** 3);
  });

  it("returns 0 for an absent value and NaN for an unparsable one", () => {
    expect(parseMemory(undefined)).toBe(0);
    expect(parseMemory("")).toBe(0);
    expect(Number.isNaN(parseMemory("512 potatoes"))).toBe(true);
    expect(Number.isNaN(parseMemory("Mi"))).toBe(true);
  });
});

describe("formatMemory", () => {
  it("prefers Gi when the value divides evenly", () => {
    expect(formatMemory(2 * 1024 ** 3)).toBe("2Gi");
  });

  it("falls back to a rounded Mi when it doesn't divide evenly into Gi", () => {
    expect(formatMemory(300 * 1024 ** 2)).toBe("300Mi");
    expect(formatMemory(1.5 * 1024 ** 3)).toBe("1536Mi");
  });

  it("falls back to a raw byte count below one Mi", () => {
    expect(formatMemory(512)).toBe("512");
  });
});

describe("parseCpu", () => {
  it("parses millicore and whole-core forms", () => {
    expect(parseCpu("500m")).toBe(500);
    expect(parseCpu("2")).toBe(2000);
    expect(parseCpu("0.5")).toBe(500);
  });

  it("returns 0 for an absent value and NaN for an unparsable one", () => {
    expect(parseCpu(undefined)).toBe(0);
    expect(Number.isNaN(parseCpu("fast"))).toBe(true);
  });
});

describe("formatCpu", () => {
  it("always renders millicores with an m suffix", () => {
    expect(formatCpu(250)).toBe("250m");
  });
});

describe("isValidMemory / isValidCpu", () => {
  it("accepts a positive, parsable value and rejects everything else", () => {
    expect(isValidMemory("64Mi")).toBe(true);
    expect(isValidMemory("0Mi")).toBe(false);
    expect(isValidMemory("nonsense")).toBe(false);
    expect(isValidMemory(undefined)).toBe(false);

    expect(isValidCpu("500m")).toBe(true);
    expect(isValidCpu("0")).toBe(false);
    expect(isValidCpu("nonsense")).toBe(false);
  });
});
