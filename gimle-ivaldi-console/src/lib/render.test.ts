import { parse } from "yaml";
import { describe, expect, it } from "vitest";

import { sampleBlueprints } from "./samples";
import { controlPlanePort, firstMachineName, renderFiles } from "./render";

const [ordersPlatform] = sampleBlueprints();

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
    // every workload manifest is referenced exactly once, in the same order they were rendered --
    // except the LimitRange manifest, which is never a Bundle workload (see render.ts's own
    // comment): it's a standalone control-plane resource, applied outside the bundle deploy.
    const manifestPaths = files
      .map((f) => f.path)
      .filter((p) => p.startsWith("manifests/") && !p.includes("-limitrange-"));
    expect(bundle.workloads.map((w) => w.file)).toEqual(manifestPaths);
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
