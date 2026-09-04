import type { SecretType } from "@/types";

export const SECRET_TYPES: SecretType[] = ["opaque", "pem-certificate", "pem-private-key"];

interface TypeSpec {
  marker: RegExp | null;
  expected: string;
}

const SPECS: Record<SecretType, TypeSpec> = {
  opaque: { marker: null, expected: "" },
  "pem-certificate": {
    marker: /-----BEGIN (CERTIFICATE)-----/,
    expected: "-----BEGIN CERTIFICATE-----",
  },
  "pem-private-key": {
    marker: /-----BEGIN ([A-Z0-9 ]*PRIVATE KEY)-----/,
    expected: "-----BEGIN [RSA|EC|ENCRYPTED ]PRIVATE KEY-----",
  },
};

const BASE64_BODY = /^[A-Za-z0-9+/=\s]*$/;

/**
 * Mirrors Fafnir's own SecretType#validate: a structural PEM-framing check only (markers present,
 * matching BEGIN/END label, non-empty base64-shaped body) -- not a real certificate/key parse.
 * Returns null when `plaintext` satisfies `type`, or a human-readable problem description
 * otherwise. A no-op for "opaque".
 */
export function pemProblem(type: SecretType, plaintext: string): string | null {
  const spec = SPECS[type];
  if (!spec.marker) return null;
  const match = spec.marker.exec(plaintext);
  if (!match) return `missing a '${spec.expected}' marker`;
  const label = match[1];
  const bodyStart = match.index + match[0].length;
  const end = `-----END ${label}-----`;
  const endAt = plaintext.indexOf(end, bodyStart);
  if (endAt < 0) return `missing '${end}' after the opening marker`;
  const body = plaintext.slice(bodyStart, endAt).trim();
  if (body.length === 0) return "no base64 body between the PEM markers";
  if (!BASE64_BODY.test(body)) return "non-base64 character in the PEM body";
  return null;
}
