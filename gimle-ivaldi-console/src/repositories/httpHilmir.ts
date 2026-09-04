import type { HilmirReport, HilmirValidatorClient, RunFile } from "./contracts";

/**
 * Talks to a real Hilmir validator over plain HTTP.
 * Wire format (v1):
 *   POST {base}/v1/validate  { files: [{ path, content }] } -> HilmirReport
 */
export class HttpHilmirValidator implements HilmirValidatorClient {
  readonly mode = "http" as const;

  constructor(readonly baseUrl: string) {}

  async validate(files: RunFile[]): Promise<HilmirReport> {
    try {
      const res = await fetch(`${this.baseUrl}/v1/validate`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ files }),
      });
      if (!res.ok) throw new Error(`hilmir ${res.status}: ${await res.text()}`);
      const body = (await res.json()) as Partial<HilmirReport>;
      return {
        ok: body.ok ?? !(body.findings ?? []).some((f) => f.severity === "error"),
        validator: body.validator ?? "hilmir",
        version: body.version ?? null,
        checkedAt: body.checkedAt ?? new Date().toISOString(),
        findings: body.findings ?? [],
        error: body.error ?? null,
      };
    } catch (error) {
      return {
        ok: false,
        validator: "hilmir",
        version: null,
        checkedAt: new Date().toISOString(),
        findings: [],
        error: error instanceof Error ? error.message : "hilmir unreachable",
      };
    }
  }
}
