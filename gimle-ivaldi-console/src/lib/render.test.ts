import { parse } from "yaml";
import { describe, expect, it } from "vitest";

import { sampleBlueprints } from "./samples";
import { controlPlanePort, firstMachineName, renderFiles } from "./render";

const [ordersPlatform] = sampleBlueprints();

const STANDALONE_KINDS = ["Service", "NetworkPolicy", "LimitRange"];

/** A manifest's own declared kind -- a filename can't be trusted for this, since a workload may
 *  legitimately be named something like "inventory-service". */
function kindOf(file: { path: string; content: string }): string | undefined {
  if (!file.path.startsWith("manifests/")) return undefined;
  return (parse(file.content) as { kind?: string }).kind;
}

function fileNamed(files: ReturnType<typeof renderFiles>, path: string) {
  const file = files.find((f) => f.path === path);
  if (!file) throw new Error(`no rendered file at ${path}`);
  return file;
}

describe("renderFiles", () => {
  it("is deterministic and doesn't mutate its input", () => {
    const before = structuredClone(ordersPlatform!);
    const first = renderFiles(ordersPlatform!);
    const second = renderFiles(ordersPlatform!);
    expect(second).toEqual(first);
    expect(ordersPlatform).toEqual(before);
  });

  it("emits exactly the file set the design spec names", () => {
    const files = renderFiles(ordersPlatform!);
    const paths = files.map((f) => f.path);
    expect(paths).toContain("topology.yaml");
    expect(paths).toContain("bundle.yaml");
    expect(paths).toContain("values.example.yaml");
    expect(paths).toContain("README.md");
    expect(paths).toContain("ivaldi.blueprint.json");
    // orders-platform-local: 1 statefulSet + 1 deployment + 1 cronJob + 1 service +
    // 1 networkPolicy + 1 limitRange
    expect(paths.filter((p) => p.startsWith("manifests/"))).toHaveLength(6);
  });

  it("orders workload manifests statefulSet, daemonSet, deployment, job, cronJob", () => {
    const files = renderFiles(ordersPlatform!);
    const manifestPaths = files.map((f) => f.path).filter((p) => p.startsWith("manifests/"));
    const kindOf = (path: string) => path.split("-").slice(1).join("-").replace(".yaml", "");
    // orders-platform-local has a statefulSet, a deployment, and a cronJob (no daemonSet/job)
    const statefulSetIndex = manifestPaths.findIndex((p) => kindOf(p).includes("inventory"));
    const deploymentIndex = manifestPaths.findIndex((p) => kindOf(p).includes("web-ui-deployment"));
    const cronIndex = manifestPaths.findIndex((p) => kindOf(p).includes("orders-report"));
    expect(statefulSetIndex).toBeLessThan(deploymentIndex);
    expect(deploymentIndex).toBeLessThan(cronIndex);
  });

  it("renders topology.yaml with every declared role and no explicit default ports", () => {
    const files = renderFiles(ordersPlatform!);
    const topology = parse(fileNamed(files, "topology.yaml").content) as Record<string, unknown>;
    expect(topology.name).toBe("orders-platform-local");
    expect((topology.machines as unknown[]).length).toBe(1);
    expect((topology.store as { replicas: unknown[] }).replicas).toHaveLength(1);
    // store's raft/client ports are the defaults in the sample, so they're omitted from output
    expect((topology.store as { replicas: Array<Record<string, unknown>> }).replicas[0]).toEqual({
      machine: "local",
    });
    expect(topology.fafnir).toBeDefined();
    expect(topology.muninn).toBeDefined();
    expect(topology.andvari).toBeDefined();
    expect((topology.agents as unknown[]).length).toBe(2);
  });

  it("renders a registry-sourced workload manifest with apiVersion, and a jar-sourced one without", () => {
    const files = renderFiles(ordersPlatform!);
    const deployment = parse(
      fileNamed(files, "manifests/02-web-ui-deployment.yaml").content,
    ) as Record<string, unknown>;
    expect(deployment.apiVersion).toBe("v1");
    expect(deployment.kind).toBe("Deployment");
    expect(deployment.replicas).toBe(2);
    expect(deployment).not.toHaveProperty("artifactPath");
  });

  it("renders bundle.yaml with tenants, config, secrets (as a values placeholder), and workload refs", () => {
    const files = renderFiles(ordersPlatform!);
    const bundle = parse(fileNamed(files, "bundle.yaml").content) as {
      kind: string;
      tenants: Array<{ id: string }>;
      config: Array<{ key: string; value: string }>;
      secrets: Array<{ key: string; value: string }>;
      workloads: Array<{ file: string }>;
    };
    expect(bundle.kind).toBe("Bundle");
    expect(bundle.tenants.map((t) => t.id)).toEqual(["orders-platform"]);
    expect(bundle.config).toEqual([
      { key: "greeting.prefix", value: "Hello", tenant: "orders-platform" },
    ]);
    expect(bundle.secrets).toEqual([
      { tenant: "orders-platform", key: "admin.token", value: "${values.admin.token}" },
    ]);
    // Every workload manifest is referenced exactly once, in the same order they were rendered.
    // Service, NetworkPolicy and LimitRange manifests are never Bundle workloads (see render.ts's
    // own comment): gimle-hilmir's BundleApplier maps a workload kind to a control-plane path
    // prefix and knows only the five workload kinds, so listing one there fails the whole deploy.
    const workloadPaths = files
      .filter((f) => f.path.startsWith("manifests/") && !STANDALONE_KINDS.includes(kindOf(f)))
      .map((f) => f.path);
    expect(bundle.workloads.map((w) => w.file)).toEqual(workloadPaths);
  });

  it("keeps Service, NetworkPolicy and LimitRange manifests out of bundle.workloads", () => {
    const files = renderFiles(ordersPlatform!);
    const bundle = parse(fileNamed(files, "bundle.yaml").content) as {
      workloads: Array<{ file: string }>;
    };
    const referenced = bundle.workloads.map((w) => w.file);
    for (const kind of STANDALONE_KINDS) {
      const standalone = files.filter((f) => kindOf(f) === kind);
      expect(standalone.length).toBeGreaterThan(0);
      for (const file of standalone) expect(referenced).not.toContain(file.path);
    }
  });

  it("README applies every standalone resource by hand, since the bundle deploy cannot", () => {
    const files = renderFiles(ordersPlatform!);
    const readme = fileNamed(files, "README.md").content;
    for (const file of files) {
      if (STANDALONE_KINDS.includes(kindOf(file))) {
        expect(readme).toContain(`gimle apply -f ${file.path}`);
      }
    }
  });

  /**
   * A workload manifest deliberately carries no resources block: the real parser answers one with
   * "not a recognized field for this manifest kind and was ignored", so emitting it would add a
   * tier-2 warning to every workload and change nothing that gets deployed. The request/limit a
   * module actually runs under come from its own gimle-module.yaml inside the jar; the values on
   * the Blueprint node feed tier-1 quota and limit-range arithmetic instead.
   */
  it("leaves resources out of a workload manifest, where the platform has no such field", () => {
    const files = renderFiles(ordersPlatform!);
    const deployment = parse(
      fileNamed(files, "manifests/02-web-ui-deployment.yaml").content,
    ) as Record<string, unknown>;

    expect(deployment).not.toHaveProperty("resources");
  });

  it("never puts a secret's actual value anywhere in the rendered files", () => {
    const files = renderFiles(ordersPlatform!);
    for (const file of files) {
      expect(file.content).not.toContain("s3cr3t");
    }
    const values = parse(fileNamed(files, "values.example.yaml").content) as Record<string, string>;
    expect(values["admin.token"]).toBe("");
  });

  it("round-trips the Blueprint itself as ivaldi.blueprint.json", () => {
    const files = renderFiles(ordersPlatform!);
    const parsed = JSON.parse(fileNamed(files, "ivaldi.blueprint.json").content);
    expect(parsed).toEqual(ordersPlatform);
  });

  it("README documents the exact hilmir/gimle commands the design spec names", () => {
    const files = renderFiles(ordersPlatform!);
    const readme = fileNamed(files, "README.md").content;
    const machine = firstMachineName(ordersPlatform!);
    const port = controlPlanePort(ordersPlatform!);
    expect(readme).toContain("hilmir validate -f topology.yaml");
    expect(readme).toContain(`hilmir up -f topology.yaml --machine ${machine}`);
    expect(readme).toContain(`hilmir deploy -f bundle.yaml`);
    expect(readme).toContain(`127.0.0.1:${port}/console`);
    expect(readme).toContain(`hilmir down --machine ${machine}`);
  });
});
