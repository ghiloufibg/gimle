import { describe, expect, it } from "vitest";

import { normaliseBlueprint } from "./import";
import { sampleBlueprints } from "./samples";

const [ordersPlatform] = sampleBlueprints();

/**
 * A malformed document used to be persisted as a blueprint that then threw on open, stranding the
 * user on an error screen whose only way out was deleting it. It is refused at the door instead,
 * with a message naming what is wrong.
 */
describe("normaliseBlueprint", () => {
  it("round-trips a real blueprint unchanged in the parts that matter", () => {
    const normalised = normaliseBlueprint(JSON.parse(JSON.stringify(ordersPlatform!)));

    expect(normalised.name).toBe(ordersPlatform!.name);
    expect(normalised.nodes).toHaveLength(ordersPlatform!.nodes.length);
    expect(normalised.edges).toHaveLength(ordersPlatform!.edges.length);
  });

  it("rejects a document that is not a blueprint at all", () => {
    expect(() => normaliseBlueprint(null)).toThrow(/blueprint object/i);
    expect(() => normaliseBlueprint([1, 2, 3])).toThrow(/blueprint object/i);
    expect(() => normaliseBlueprint({ nodes: [], edges: [] })).toThrow(/name/i);
    expect(() => normaliseBlueprint({ name: "x", edges: [] })).toThrow(/nodes/i);
    expect(() => normaliseBlueprint({ name: "x", nodes: [] })).toThrow(/edges/i);
  });

  it("names the offending node when its kind is unknown", () => {
    expect(() =>
      normaliseBlueprint({
        name: "x",
        nodes: [{ id: "n1", kind: "wormhole", data: {} }],
        edges: [],
      }),
    ).toThrow(/n1.*wormhole/i);
  });

  it("fills a node's missing fields from its own kind's defaults rather than opening undefined", () => {
    const normalised = normaliseBlueprint({
      name: "x",
      nodes: [{ id: "m1", kind: "machine", data: { name: "local" } }],
      edges: [],
    });

    expect(normalised.nodes[0].data).toMatchObject({ name: "local", host: "127.0.0.1" });
    expect(normalised.nodes[0].position).toEqual({ x: 0, y: 0 });
  });

  it("rejects an edge pointing at a node the file does not contain", () => {
    expect(() =>
      normaliseBlueprint({
        name: "x",
        nodes: [{ id: "m1", kind: "machine", data: {} }],
        edges: [{ kind: "placedOn", source: "m1", target: "ghost" }],
      }),
    ).toThrow(/not in the file/i);
  });
});
