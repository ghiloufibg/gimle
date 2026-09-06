import { parse } from "yaml";
import { describe, expect, it } from "vitest";

import { sampleBlueprints } from "./samples";
import { controlPlaneHost, controlPlanePort, firstMachineName, renderFiles } from "./render";

const [ordersPlatform] = sampleBlueprints();

const STANDALONE_KINDS: (string | undefined)[] = ["Service", "NetworkPolicy", "LimitRange"];

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

  it("renders every workload manifest as v1, with no deprecated local artifactPath", () => {
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
      expect(kind).toBeDefined();
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

describe("renderFiles, on input the file formats cannot take verbatim", () => {
  function withWorkloadNamed(name: string) {
    const bp = structuredClone(ordersPlatform!);
    const workload = bp.nodes.find((n) => n.kind === "deployment")!;
    (workload.data as { name: string }).name = name;
    return renderFiles(bp);
  }

  it("sanitises a workload name into its manifest path but leaves the name itself alone", () => {
    const files = withWorkloadNamed('a/b\\c "q" x');
    const path = files.map((f) => f.path).find((p) => p.includes("a-b-c-q-x"))!;
    expect(path).toBe("manifests/02-a-b-c-q-x.yaml");
    // one path component under manifests/, with nothing a zip entry or Windows would refuse
    expect(path.split("/")).toHaveLength(2);
    const doc = parse(files.find((f) => f.path === path)!.content) as { name: string };
    expect(doc.name).toBe('a/b\\c "q" x');
  });

  it("caps a very long name, which no filesystem would accept as one path component", () => {
    const files = withWorkloadNamed("L".repeat(300));
    const path = files.map((f) => f.path).find((p) => p.includes("llll"))!;
    expect(path.replace("manifests/", "").length).toBeLessThanOrEqual(70);
  });

  it("quotes the scalars a YAML 1.1 reader would take for a boolean or a date", () => {
    const bp = structuredClone(ordersPlatform!);
    const config = bp.nodes.find((n) => n.kind === "configEntry")!;
    (config.data as { value: string }).value = "yes";
    const bundle = renderFiles(bp).find((f) => f.path === "bundle.yaml")!.content;
    expect(bundle).toContain('value: "yes"');
  });

  it("unions jvm flags across replicas of a role, since the format holds one list per role", () => {
    const bp = structuredClone(ordersPlatform!);
    const store = bp.nodes.find((n) => n.kind === "store")!;
    const second = structuredClone(store);
    second.id = "r-store-2";
    (store.data as { jvmFlags?: string[] }).jvmFlags = ["-Xmx512m"];
    (second.data as { jvmFlags?: string[] }).jvmFlags = ["-XX:+UseZGC"];
    bp.nodes.push(second);
    const topology = parse(renderFiles(bp).find((f) => f.path === "topology.yaml")!.content) as {
      jvm: { store: string[] };
    };
    expect(topology.jvm.store.sort()).toEqual(["-XX:+UseZGC", "-Xmx512m"]);
  });

  it("brings up and tears down every machine in the README, not just the first", () => {
    const bp = structuredClone(ordersPlatform!);
    const machine = structuredClone(bp.nodes.find((n) => n.kind === "machine")!);
    machine.id = "m-beta";
    (machine.data as { name: string }).name = "beta";
    bp.nodes.push(machine);
    const readme = renderFiles(bp).find((f) => f.path === "README.md")!.content;
    expect(readme).toContain("hilmir up -f topology.yaml --machine local");
    expect(readme).toContain("hilmir up -f topology.yaml --machine beta");
    expect(readme).toContain("hilmir down --machine beta");
  });

  it("README's connect address names the machine the control plane is actually placed on", () => {
    const bp = structuredClone(ordersPlatform!);
    const machine = structuredClone(bp.nodes.find((n) => n.kind === "machine")!);
    machine.id = "m-beta";
    (machine.data as { name: string; host: string }).name = "beta";
    (machine.data as { name: string; host: string }).host = "127.0.0.2";
    bp.nodes.push(machine);
    const controlPlane = bp.nodes.find((n) => n.kind === "controlPlane")!;
    (controlPlane.data as { machine: string }).machine = "beta";

    expect(controlPlaneHost(bp)).toBe("127.0.0.2");
    const readme = renderFiles(bp).find((f) => f.path === "README.md")!.content;
    expect(readme).toContain(`127.0.0.2:${controlPlanePort(bp)}/console`);
    expect(readme).not.toContain(`127.0.0.1:${controlPlanePort(bp)}/console`);
  });

  it("carries a tenant's isolation posture into bundle.yaml, where the platform reads it", () => {
    const bp = structuredClone(ordersPlatform!);
    (
      bp.nodes.find((n) => n.kind === "tenant")!.data as { isolationPosture?: string }
    ).isolationPosture = "DENY_BY_DEFAULT";
    const bundle = parse(renderFiles(bp).find((f) => f.path === "bundle.yaml")!.content) as {
      tenants: Array<{ isolationPosture?: string }>;
    };
    expect(bundle.tenants[0].isolationPosture).toBe("DENY_BY_DEFAULT");
  });
});

describe("a jar-sourced workload", () => {
  it("keeps its jar out of the manifest and records it in ivaldi.artifacts.yaml instead", () => {
    const bp = structuredClone(ordersPlatform!);
    const cron = bp.nodes.find((n) => n.kind === "cronJob")!;
    (cron.data as { artifact: { source: string; path: string } }).artifact = {
      source: "jar",
      path: "/tmp/report.jar",
    };
    const files = renderFiles(bp);
    const manifest = files.find((f) => kindOf(f) === "CronJob")!;
    const doc = parse(manifest.content) as {
      artifactPath?: string;
      jobTemplate: { artifactPath?: string };
    };

    expect(doc).not.toHaveProperty("artifactPath");
    expect(doc.jobTemplate).not.toHaveProperty("artifactPath");

    const sidecar = parse(fileNamed(files, "ivaldi.artifacts.yaml").content) as {
      artifacts: Array<{ manifest: string; path: string }>;
    };
    const entry = sidecar.artifacts.find((a) => a.manifest === manifest.path)!;
    expect(entry.path).toBe("/tmp/report.jar");
  });

  it("omits the sidecar entirely for a file set whose workloads all come from the registry", () => {
    const bp = structuredClone(ordersPlatform!);
    for (const node of bp.nodes) {
      const data = node.data as { artifact?: { source: string } };
      if (data.artifact?.source === "jar") data.artifact = { source: "registry" } as never;
    }
    expect(renderFiles(bp).some((f) => f.path === "ivaldi.artifacts.yaml")).toBe(false);
  });
});

describe("a Service's targetPort", () => {
  it("is omitted entirely when blank, so the platform's own OptionalInt default (= port) applies", () => {
    const bp = structuredClone(ordersPlatform!);
    const service = bp.nodes.find((n) => n.kind === "service")!;
    (service.data as { targetPort?: number }).targetPort = undefined;
    const files = renderFiles(bp);
    const manifest = files.find((f) => kindOf(f) === "Service")!;

    expect(parse(manifest.content)).not.toHaveProperty("targetPort");
  });

  it("is rendered as the declared number when set", () => {
    const bp = structuredClone(ordersPlatform!);
    const service = bp.nodes.find((n) => n.kind === "service")!;
    (service.data as { targetPort?: number }).targetPort = 9999;
    const files = renderFiles(bp);
    const manifest = files.find((f) => kindOf(f) === "Service")!;

    expect((parse(manifest.content) as { targetPort: number }).targetPort).toBe(9999);
  });
});

describe("a DaemonSet's tolerateAllTaints", () => {
  it("is omitted when false or unset, matching the field's own false default", () => {
    const bp = structuredClone(ordersPlatform!);
    const deployment = bp.nodes.find((n) => n.kind === "deployment")!;
    deployment.kind = "daemonSet";
    const files = renderFiles(bp);
    const manifest = files.find((f) => kindOf(f) === "DaemonSet")!;

    expect(parse(manifest.content)).not.toHaveProperty("tolerateAllTaints");
  });

  it("is rendered as true when set, and only for a DaemonSet", () => {
    const bp = structuredClone(ordersPlatform!);
    const deployment = bp.nodes.find((n) => n.kind === "deployment")!;
    deployment.kind = "daemonSet";
    (deployment.data as { tolerateAllTaints?: boolean }).tolerateAllTaints = true;
    const files = renderFiles(bp);
    const manifest = files.find((f) => kindOf(f) === "DaemonSet")!;

    expect((parse(manifest.content) as { tolerateAllTaints: boolean }).tolerateAllTaints).toBe(
      true,
    );
  });
});

describe("the release a blueprint deploys under", () => {
  it("is named after the blueprint's id, so a rename does not fork its history", () => {
    const bp = structuredClone(ordersPlatform!);
    bp.id = "bp-orders";
    const before = parse(renderFiles(bp).find((f) => f.path === "bundle.yaml")!.content) as {
      name: string;
    };
    bp.name = "orders-platform-renamed";
    const after = parse(renderFiles(bp).find((f) => f.path === "bundle.yaml")!.content) as {
      name: string;
    };

    expect(before.name).toBe("bp-orders");
    expect(after.name).toBe(before.name);
  });
});
