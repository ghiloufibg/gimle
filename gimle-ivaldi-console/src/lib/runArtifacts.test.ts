import { describe, expect, it } from "vitest";

import { artifactsFromLog } from "./runArtifacts";
import type { RunLogLine } from "@/repositories";

function lines(...texts: string[]): RunLogLine[] {
  return texts.map((text, i) => ({
    seq: i,
    ts: "2026-09-05T09:00:00Z",
    level: "info" as const,
    source: "ivaldi",
    text,
  }));
}

describe("artifactsFromLog", () => {
  /**
   * The backend stamps every line "[<instant>] ". Anchoring the match at the start of the line
   * therefore found nothing at all, and the Artifacts panel showed its empty state for the whole
   * life of a running cluster while the console beside it printed every push.
   */
  it("finds a push in a line carrying the backend's own timestamp prefix", () => {
    const found = artifactsFromLog(
      lines(
        "[2026-09-05T09:24:30.535192753Z] pushed artifact com.gimle.examples.hello@1.0.0 from /tmp/hello.jar",
      ),
    );

    expect(found).toEqual([
      { moduleId: "com.gimle.examples.hello", version: "1.0.0", path: "/tmp/hello.jar" },
    ]);
  });

  it("ignores lines that are not pushes, and reports each artifact once", () => {
    const found = artifactsFromLog(
      lines(
        "[t] validated 6 file(s), 0 errors",
        "[t] pushed artifact a@1 from /tmp/a.jar",
        "[t] pushed artifact a@1 from /tmp/a.jar",
        "[t] pushed artifact b@2 from /tmp/b.jar",
      ),
    );

    expect(found.map((a) => a.moduleId)).toEqual(["a", "b"]);
  });
});
