import { jsonBody, requestJson, requestOk } from "./apiClient";
import type { ClusterConnection, ClustersRepository } from "./contracts";

/**
 * Talks to the real gimle-ivaldi backend's /api/clusters surface. Unlike blueprints, the id is
 * minted client-side (see useClustersStore's newCluster/uid) and every save is a PUT upsert at
 * that id -- ClusterStore accepts an id it has never seen before the same way it accepts one it
 * already has, so there is no separate create call to make.
 */
export class HttpClustersRepository implements ClustersRepository {
  async list(): Promise<ClusterConnection[]> {
    const raw = await requestJson<ClusterConnection[]>("/api/clusters");
    return Array.isArray(raw) ? raw : [];
  }

  async get(id: string): Promise<ClusterConnection | undefined> {
    try {
      return await requestJson<ClusterConnection>(`/api/clusters/${encodeURIComponent(id)}`);
    } catch {
      return undefined;
    }
  }

  async save(cluster: ClusterConnection): Promise<ClusterConnection> {
    return requestJson<ClusterConnection>(`/api/clusters/${encodeURIComponent(cluster.id)}`, {
      method: "PUT",
      body: jsonBody(cluster),
    });
  }

  async delete(id: string): Promise<void> {
    await requestOk(`/api/clusters/${encodeURIComponent(id)}`, { method: "DELETE" });
  }
}
