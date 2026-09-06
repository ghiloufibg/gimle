import { describe, expect, it } from "vitest";
import { coordinateForPickedFile } from "./pushCoordinate";

describe("coordinateForPickedFile", () => {
  it("drops a coordinate the previous jar declared rather than carrying it onto a new one", () => {
    const derived = { moduleId: "com.example.greeter", version: "1.0.0", derivedFrom: "a.jar" };
    expect(coordinateForPickedFile(derived, "")).toEqual({ moduleId: "", version: "" });
  });

  it("falls back to the coordinate the screen was opened for, not to nothing", () => {
    const derived = { moduleId: "com.example.greeter", version: "1.0.0", derivedFrom: "a.jar" };
    expect(coordinateForPickedFile(derived, "com.example.other")).toEqual({
      moduleId: "com.example.other",
      version: "",
    });
  });

  it("keeps a hand-typed coordinate -- that one is the operator's own input", () => {
    const typed = { moduleId: "com.example.vessel", version: "2.1.0", derivedFrom: null };
    expect(coordinateForPickedFile(typed, "")).toEqual({
      moduleId: "com.example.vessel",
      version: "2.1.0",
    });
  });
});
