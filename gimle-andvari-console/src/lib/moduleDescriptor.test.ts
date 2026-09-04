import { describe, expect, it } from "vitest";

import { readModuleCoordinate } from "./moduleDescriptor";
import { buildZip } from "./zipTestSupport";

function jarWithDescriptor(yaml: string): Blob {
  const zip = buildZip([
    { name: "module-info.class", data: new Uint8Array([1, 2, 3]), method: 0 },
    { name: "META-INF/gimle/gimle-module.yaml", data: new TextEncoder().encode(yaml) },
  ]);
  return new Blob([zip]);
}

describe("readModuleCoordinate", () => {
  it("derives moduleId/version from a real gimle-module.yaml, the same fields the CLI reads", async () => {
    const jar = jarWithDescriptor(
      [
        "name: com.gimle.examples.art2",
        "version: 2.0.0",
        "isolation:",
        "  tier: TIER_2",
        "resources:",
        "  request:",
        "    memory: 32Mi",
      ].join("\n"),
    );

    const coordinate = await readModuleCoordinate(jar);

    expect(coordinate).toEqual({ moduleId: "com.gimle.examples.art2", version: "2.0.0" });
  });

  it("this is exactly the finding scenario: the jar's own coordinate wins over any typed one", async () => {
    // The regression this covers: a jar whose own descriptor declares art2:2.0.0 must never be
    // storable under an unrelated typed coordinate like wrongname:9.9.9 -- the dialog now derives
    // the coordinate straight from this reader rather than trusting free-typed input.
    const jar = jarWithDescriptor("name: com.gimle.examples.art2\nversion: 2.0.0\n");

    const coordinate = await readModuleCoordinate(jar);

    expect(coordinate?.moduleId).toBe("com.gimle.examples.art2");
    expect(coordinate?.moduleId).not.toBe("com.gimle.examples.wrongname");
  });

  it("tolerates quoted scalars", async () => {
    const jar = jarWithDescriptor("name: \"com.gimle.examples.art2\"\nversion: '2.0.0'\n");

    const coordinate = await readModuleCoordinate(jar);

    expect(coordinate).toEqual({ moduleId: "com.gimle.examples.art2", version: "2.0.0" });
  });

  it("strips a trailing comment on an unquoted scalar", async () => {
    const jar = jarWithDescriptor(
      "name: com.gimle.examples.art2 # the module id\nversion: 2.0.0\n",
    );

    const coordinate = await readModuleCoordinate(jar);

    expect(coordinate).toEqual({ moduleId: "com.gimle.examples.art2", version: "2.0.0" });
  });

  it("returns null for a vessel jar with no bundled descriptor", async () => {
    const zip = buildZip([{ name: "module-info.class", data: new Uint8Array([1, 2, 3]) }]);

    const coordinate = await readModuleCoordinate(new Blob([zip]));

    expect(coordinate).toBeNull();
  });

  it("returns null when the descriptor is missing a version", async () => {
    const jar = jarWithDescriptor("name: com.gimle.examples.art2\n");

    const coordinate = await readModuleCoordinate(jar);

    expect(coordinate).toBeNull();
  });

  it("returns null for a file that isn't a jar at all, rather than throwing", async () => {
    const notAJar = new Blob([new TextEncoder().encode("definitely not a zip")]);

    await expect(readModuleCoordinate(notAJar)).resolves.toBeNull();
  });
});
