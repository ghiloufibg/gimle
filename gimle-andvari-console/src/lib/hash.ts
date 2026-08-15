/**
 * Hex-encoded SHA-256 of `bytes`, via the platform Web Crypto API (available in every browser
 * this console targets, and in the Vitest/Bun test runtime alike) rather than pulling in a
 * hashing library for one digest.
 */
export async function sha256Hex(bytes: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

/**
 * Whether `bytes` hashes to `expectedHex` -- closes the gap where AndvariServer computes and
 * reports a real sha256 (the `X-Gimle-Artifact-Sha256` header on `HEAD`/`GET
 * /artifacts/{moduleId}/{version}`, and the same value `GET /artifacts/{moduleId}` lists per
 * version) but nothing on the client ever checked a downloaded jar's bytes against it: a
 * corrupted transfer or a tampering proxy between the browser and the registry would otherwise be
 * saved to the operator's disk indistinguishably from the real artifact. Case-insensitive since
 * hex digests are conventionally lowercase here but that's not a wire-format guarantee worth
 * failing a legitimate download over.
 */
export async function verifySha256(bytes: ArrayBuffer, expectedHex: string): Promise<boolean> {
  const actual = await sha256Hex(bytes);
  return actual === expectedHex.toLowerCase();
}
