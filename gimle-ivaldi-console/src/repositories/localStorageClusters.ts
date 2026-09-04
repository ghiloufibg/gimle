import type { ClusterConnection, ClustersRepository } from "./contracts";

const KEY = "ivaldi.clusters.v1";

function now(): string {
  return new Date().toISOString();
}

function seed(): ClusterConnection[] {
  return [
    {
      id: "cluster-local",
      name: "local-dev",
      environment: "local",
      controlPlaneUrl: "http://127.0.0.1:8080",
      runnerUrl: null,
      clientCertPath: "",
      clientKeyPath: "",
      description: "Simulated local Gimlé cluster (no runner daemon configured).",
      createdAt: now(),
      updatedAt: now(),
    },
  ];
}

function read(): ClusterConnection[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = window.localStorage.getItem(KEY);
    if (raw === null) {
      const seeded = seed();
      window.localStorage.setItem(KEY, JSON.stringify(seeded));
      return seeded;
    }
    const parsed = JSON.parse(raw) as ClusterConnection[];
    return Array.isArray(parsed)
      ? parsed.map((c) => ({
          ...c,
          clientCertPath: c.clientCertPath ?? "",
          clientKeyPath: c.clientKeyPath ?? "",
        }))
      : [];
  } catch {
    return [];
  }
}

function write(list: ClusterConnection[]): void {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(KEY, JSON.stringify(list));
}

/** Kept only for Vitest coverage and as a reference implementation; not the composition root's
 * choice any more now that a real /api/clusters backend exists (see HttpClustersRepository). */
export class LocalStorageClustersRepository implements ClustersRepository {
  async list(): Promise<ClusterConnection[]> {
    return read().sort((a, b) => a.name.localeCompare(b.name));
  }

  async get(id: string): Promise<ClusterConnection | undefined> {
    return read().find((c) => c.id === id);
  }

  async save(cluster: ClusterConnection): Promise<ClusterConnection> {
    const next: ClusterConnection = { ...cluster, updatedAt: now() };
    const list = read();
    const idx = list.findIndex((c) => c.id === next.id);
    if (idx >= 0) list[idx] = next;
    else list.push(next);
    write(list);
    return next;
  }

  async delete(id: string): Promise<void> {
    write(read().filter((c) => c.id !== id));
  }
}
