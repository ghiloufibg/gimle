import { jsonBody, requestJson } from "./apiClient";
import type { HilmirFinding, HilmirReport, HilmirValidatorClient, RunFile } from "./contracts";

/** Wire shape of POST /api/validate. */
interface RawValidationResponse {
  findings?: RawFinding[];
}

interface RawFinding {
  code?: string;
  severity?: string;
  message?: string;
  file?: string;
  path?: string;
  resource?: string;
}

function mapFinding(raw: RawFinding): HilmirFinding {
  const severity =
    raw.severity === "error" || raw.severity === "warning" || raw.severity === "info"
      ? raw.severity
      : "info";
  return {
    code: raw.code ?? "UNKNOWN",
    severity,
    message: raw.message ?? "",
    ...(raw.file ? { file: raw.file } : {}),
    ...(raw.path ? { path: raw.path } : {}),
    ...(raw.resource ? { resource: raw.resource } : {}),
  };
}

/**
 * Tier-2 validation against the real platform parsers.
 * Same-origin POST /api/validate with the exact rendered file paths.
 */
export class HttpValidationRepository implements HilmirValidatorClient {
  readonly mode = "http" as const;
  readonly baseUrl = null;

  async validate(files: RunFile[]): Promise<HilmirReport> {
    const checkedAt = new Date().toISOString();
    try {
      const body = await requestJson<RawValidationResponse>("/api/validate", {
        method: "POST",
        body: jsonBody({ files: files.map((f) => ({ path: f.path, content: f.content })) }),
      });
      const findings = (body.findings ?? []).map(mapFinding);
      return {
        ok: !findings.some((f) => f.severity === "error"),
        validator: "gimle",
        version: null,
        checkedAt,
        findings,
        error: null,
      };
    } catch (error) {
      return {
        ok: false,
        validator: "gimle",
        version: null,
        checkedAt,
        findings: [],
        error: error instanceof Error ? error.message : "couldn't validate",
      };
    }
  }
}
