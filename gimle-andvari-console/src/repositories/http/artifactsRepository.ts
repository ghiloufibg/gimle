import type { ArtifactCatalogEntry, ArtifactPushResult } from "@/types";
import type { ArtifactsRepository } from "../types";
import {
  apiUrl,
  requestBinaryDownload,
  requestBinaryUpload,
  requestJson,
  requestOk,
} from "./apiClient";

export class HttpArtifactsRepository implements ArtifactsRepository {
  catalog(): Promise<string[]> {
    return requestJson<string[]>("/artifacts");
  }

  module(moduleId: string): Promise<ArtifactCatalogEntry> {
    return requestJson<ArtifactCatalogEntry>(`/artifacts/${encodeURIComponent(moduleId)}`);
  }

  async push(moduleId: string, version: string, file: File): Promise<ArtifactPushResult> {
    return requestBinaryUpload<ArtifactPushResult>(
      `/artifacts/${encodeURIComponent(moduleId)}/${encodeURIComponent(version)}`,
      "PUT",
      file,
    );
  }

  async remove(moduleId: string, version: string): Promise<void> {
    await requestOk(
      `/artifacts/${encodeURIComponent(moduleId)}/${encodeURIComponent(version)}`,
      "DELETE",
    );
  }

  downloadUrl(moduleId: string, version: string): string {
    return apiUrl(`/artifacts/${encodeURIComponent(moduleId)}/${encodeURIComponent(version)}`);
  }

  download(moduleId: string, version: string): Promise<Blob> {
    return requestBinaryDownload(
      `/artifacts/${encodeURIComponent(moduleId)}/${encodeURIComponent(version)}`,
    );
  }
}
