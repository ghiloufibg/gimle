import type { ArtifactVersion } from "@/types";
import type { ArtifactsRepository } from "@/repositories/artifacts";
import { ApiError, requestJson, requestOk } from "./apiClient";

/**
 * Reads through the control plane's {@code /artifacts/*} proxy rather than talking to Andvari
 * directly -- the console only ever holds a browser session against the control plane, and the
 * proxy is what re-checks that principal's ARTIFACT permissions on the way through.
 *
 * <p>Deliberately uncached: the registry is the authority on which coordinates exist, and a push
 * or delete performed anywhere else (the CLI, a build pipeline) has to show up on the next read.
 */
export class HttpArtifactsRepository implements ArtifactsRepository {
  async fetchCatalog(): Promise<string[]> {
    return requestJson<string[]>("GET", "/artifacts");
  }

  async fetchVersions(moduleId: string): Promise<ArtifactVersion[]> {
    try {
      const raw = await requestJson<{ moduleId: string; versions: ArtifactVersion[] }>(
        "GET",
        `/artifacts/${encodeURIComponent(moduleId)}`,
      );
      return raw.versions;
    } catch (e) {
      // The registry answers 404 for a module with nothing stored, which is a normal state to
      // land in rather than a failure -- deleting a module's last version leaves the id in a
      // just-fetched catalog until the next refresh. Surfacing that as an error banner would
      // report the console's own stale list back to the operator as a registry fault.
      if (e instanceof ApiError && e.status === 404) return [];
      throw e;
    }
  }

  async remove(moduleId: string, version: string): Promise<void> {
    await requestOk(
      "DELETE",
      `/artifacts/${encodeURIComponent(moduleId)}/${encodeURIComponent(version)}`,
    );
  }
}
