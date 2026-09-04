import { parse } from "yaml";

import type { HilmirFinding, HilmirReport, HilmirValidatorClient, RunFile } from "./contracts";

interface Role {
  machine?: string;
  port?: number;
  raftPort?: number;
  clientPort?: number;
  nodeId?: string;
  gossipPort?: number;
}

interface Topology {
  name?: string;
  machines?: { name?: string; host?: string }[];
  security?: { mtls?: boolean };
  store?: { replicas?: Role[] };
  controlPlane?: { replicas?: Role[] };
  fafnir?: { replicas?: Role[] };
  muninn?: { replicas?: Role[] };
  andvari?: { replicas?: Role[] };
  agents?: Role[];
}

interface ManifestDoc {
  apiVersion?: string;
  kind?: string;
  name?: string;
  tenantId?: string;
  replicas?: number;
  artifactPath?: string;
  schedule?: string;
  module?: { name?: string; version?: string };
  jobTemplate?: { module?: { name?: string; version?: string } };
}

const SEMVER = /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/;

/**
 * Stand-in for the Hilmir CLI validator. It reads exactly the payload a real
 * Hilmir gets (topology.yaml + manifests/*.yaml) and reports findings in
 * Hilmir's own wire shape, with Hilmir codes — never Ivaldi rule codes.
 */
export class MockHilmirValidator implements HilmirValidatorClient {
  readonly mode = "mock" as const;
  readonly baseUrl = null;

  async validate(files: RunFile[]): Promise<HilmirReport> {
    await new Promise((resolve) => setTimeout(resolve, 320));
    const findings: HilmirFinding[] = [];
    const topologyFile = files.find((f) => f.path === "topology.yaml");

    if (!topologyFile) {
      findings.push({
        code: "HIL0001",
        severity: "error",
        message: "no topology.yaml in the bundle",
      });
    } else {
      let topology: Topology | null = null;
      try {
        topology = (parse(topologyFile.content) ?? {}) as Topology;
      } catch (error) {
        findings.push({
          code: "HIL0002",
          severity: "error",
          message: `topology.yaml is not valid YAML: ${error instanceof Error ? error.message : "parse error"}`,
          file: "topology.yaml",
        });
      }
      if (topology) findings.push(...validateTopology(topology));
    }

    for (const file of files.filter((f) => f.path.startsWith("manifests/"))) {
      let doc: ManifestDoc | null = null;
      try {
        doc = (parse(file.content) ?? {}) as ManifestDoc;
      } catch (error) {
        findings.push({
          code: "HIL0002",
          severity: "error",
          message: `manifest is not valid YAML: ${error instanceof Error ? error.message : "parse error"}`,
          file: file.path,
        });
      }
      if (doc) findings.push(...validateManifest(doc, file.path));
    }

    findings.push({
      code: "HIL9000",
      severity: "info",
      message: `hilmir validate: ${files.length} file${files.length === 1 ? "" : "s"} parsed`,
    });

    return {
      ok: !findings.some((f) => f.severity === "error"),
      validator: "hilmir (simulated)",
      version: "0.9.3-mock",
      checkedAt: new Date().toISOString(),
      findings,
      error: null,
    };
  }
}

function validateTopology(t: Topology): HilmirFinding[] {
  const out: HilmirFinding[] = [];
  const file = "topology.yaml";
  const machines = new Set((t.machines ?? []).map((m) => m.name).filter(Boolean) as string[]);

  if (!t.name)
    out.push({
      code: "HIL1000",
      severity: "error",
      message: "topology.name is required",
      file,
      path: "name",
    });
  if (machines.size === 0)
    out.push({
      code: "HIL1001",
      severity: "error",
      message: "no machines declared",
      file,
      path: "machines",
    });

  const store = t.store?.replicas ?? [];
  if (store.length === 0)
    out.push({
      code: "HIL1010",
      severity: "error",
      message: "store has no replicas",
      file,
      path: "store.replicas",
    });
  else if (store.length % 2 === 0)
    out.push({
      code: "HIL1011",
      severity: "warning",
      message: `store has ${store.length} replicas; raft needs an odd count to keep quorum`,
      file,
      path: "store.replicas",
    });

  if ((t.controlPlane?.replicas ?? []).length === 0)
    out.push({
      code: "HIL1020",
      severity: "error",
      message: "controlPlane has no replicas; nothing can accept a bundle",
      file,
      path: "controlPlane.replicas",
    });

  const groups: [string, Role[]][] = [
    ["store", store],
    ["controlPlane", t.controlPlane?.replicas ?? []],
    ["fafnir", t.fafnir?.replicas ?? []],
    ["muninn", t.muninn?.replicas ?? []],
    ["andvari", t.andvari?.replicas ?? []],
    ["agents", t.agents ?? []],
  ];

  for (const [group, roles] of groups) {
    roles.forEach((role, index) => {
      const path = group === "agents" ? `agents[${index}]` : `${group}.replicas[${index}]`;
      if (role.machine && machines.size && !machines.has(role.machine))
        out.push({
          code: "HIL1030",
          severity: "error",
          message: `${path}.machine "${role.machine}" is not a declared machine`,
          file,
          path,
          resource: `${group}/${role.machine}`,
        });
      for (const [key, port] of Object.entries({
        port: role.port,
        raftPort: role.raftPort,
        clientPort: role.clientPort,
        gossipPort: role.gossipPort,
      })) {
        if (typeof port === "number" && port > 0 && port < 1024)
          out.push({
            code: "HIL1040",
            severity: "warning",
            message: `${path}.${key} ${port} is privileged; the JVM will need root to bind it`,
            file,
            path: `${path}.${key}`,
          });
      }
    });
  }

  if ((t.agents ?? []).length === 0)
    out.push({
      code: "HIL1050",
      severity: "warning",
      message: "no agents declared; workloads have nowhere to be scheduled",
      file,
      path: "agents",
    });

  if (t.security?.mtls === false)
    out.push({
      code: "HIL1060",
      severity: "info",
      message: "mTLS is off; inter-process traffic is unencrypted",
      file,
      path: "security.mtls",
    });

  return out;
}

function validateManifest(doc: ManifestDoc, file: string): HilmirFinding[] {
  const out: HilmirFinding[] = [];
  const kind = doc.kind ?? "Resource";
  const resource = `${kind.charAt(0).toLowerCase()}${kind.slice(1)}/${doc.name ?? "unnamed"}`;

  if (!doc.name)
    out.push({
      code: "HIL2000",
      severity: "error",
      message: "manifest has no name",
      file,
      path: "name",
    });
  if (!doc.tenantId)
    out.push({
      code: "HIL2001",
      severity: "error",
      message: `${resource} has no tenantId; the control plane cannot place it`,
      file,
      path: "tenantId",
      resource,
    });

  const mod = doc.module ?? doc.jobTemplate?.module;
  if (mod) {
    if (!mod.name)
      out.push({
        code: "HIL2010",
        severity: "error",
        message: `${resource} module.name is missing`,
        file,
        path: "module.name",
        resource,
      });
    if (!mod.version)
      out.push({
        code: "HIL2011",
        severity: "error",
        message: `${resource} module.version is missing`,
        file,
        path: "module.version",
        resource,
      });
    else if (!SEMVER.test(mod.version))
      out.push({
        code: "HIL2012",
        severity: "warning",
        message: `${resource} module.version "${mod.version}" is not semver; Andvari resolution may be ambiguous`,
        file,
        path: "module.version",
        resource,
      });
  }

  if (doc.artifactPath) {
    if (!doc.artifactPath.startsWith("/"))
      out.push({
        code: "HIL2020",
        severity: "warning",
        message: `${resource} artifactPath "${doc.artifactPath}" is relative; it resolves against the runner's cwd`,
        file,
        path: "artifactPath",
        resource,
      });
    if (!doc.artifactPath.endsWith(".jar"))
      out.push({
        code: "HIL2021",
        severity: "error",
        message: `${resource} artifactPath must point at a .jar`,
        file,
        path: "artifactPath",
        resource,
      });
  }

  if (kind === "Deployment" && (doc.replicas ?? 1) < 2)
    out.push({
      code: "HIL2030",
      severity: "info",
      message: `${resource} runs a single replica; a rolling restart drops all traffic`,
      file,
      path: "replicas",
      resource,
    });

  if (kind === "CronJob" && doc.schedule && doc.schedule.trim().split(/\s+/).length !== 5)
    out.push({
      code: "HIL2040",
      severity: "error",
      message: `${resource} schedule "${doc.schedule}" is not a 5-field cron expression`,
      file,
      path: "schedule",
      resource,
    });

  return out;
}
