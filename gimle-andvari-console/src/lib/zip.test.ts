import { describe, expect, it } from "vitest";

import { readZipEntry } from "./zip";
import { buildZip } from "./zipTestSupport";

function blobOf(bytes: Uint8Array): Blob {
  return new Blob([bytes]);
}

describe("readZipEntry", () => {
  it("reads a deflate-compressed entry back out", async () => {
    const data = new TextEncoder().encode("name: com.example.app\nversion: 1.0.0\n");
    const zip = buildZip([{ name: "META-INF/gimle/gimle-module.yaml", data, method: 8 }]);

    const read = await readZipEntry(blobOf(zip), "META-INF/gimle/gimle-module.yaml");

    expect(read).not.toBeNull();
    expect(new TextDecoder().decode(read!)).toBe(new TextDecoder().decode(data));
  });

  it("reads a stored (uncompressed) entry back out", async () => {
    const data = new TextEncoder().encode("name: com.example.app\nversion: 2.0.0\n");
    const zip = buildZip([{ name: "META-INF/gimle/gimle-module.yaml", data, method: 0 }]);

    const read = await readZipEntry(blobOf(zip), "META-INF/gimle/gimle-module.yaml");

    expect(read).not.toBeNull();
    expect(new TextDecoder().decode(read!)).toBe(new TextDecoder().decode(data));
  });

  it("finds the right entry among several", async () => {
    const wanted = new TextEncoder().encode("name: com.example.app\nversion: 3.0.0\n");
    const zip = buildZip([
      { name: "module-info.class", data: new Uint8Array([1, 2, 3]), method: 0 },
      { name: "META-INF/MANIFEST.MF", data: new TextEncoder().encode("Manifest-Version: 1.0\n") },
      { name: "META-INF/gimle/gimle-module.yaml", data: wanted },
      { name: "com/example/App.class", data: new Uint8Array([9, 9, 9]) },
    ]);

    const read = await readZipEntry(blobOf(zip), "META-INF/gimle/gimle-module.yaml");

    expect(new TextDecoder().decode(read!)).toBe(new TextDecoder().decode(wanted));
  });

  it("returns null when the entry doesn't exist (a vessel jar with no descriptor)", async () => {
    const zip = buildZip([{ name: "module-info.class", data: new Uint8Array([1, 2, 3]) }]);

    const read = await readZipEntry(blobOf(zip), "META-INF/gimle/gimle-module.yaml");

    expect(read).toBeNull();
  });

  it("returns null for a file that isn't a well-formed ZIP at all", async () => {
    const notAZip = new TextEncoder().encode("this is not a jar file");

    const read = await readZipEntry(blobOf(notAZip), "META-INF/gimle/gimle-module.yaml");

    expect(read).toBeNull();
  });

  it("returns null for an empty file", async () => {
    const read = await readZipEntry(blobOf(new Uint8Array(0)), "META-INF/gimle/gimle-module.yaml");

    expect(read).toBeNull();
  });
});
