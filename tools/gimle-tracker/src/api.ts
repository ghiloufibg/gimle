import type { GimleData } from "./types";

export async function fetchGimleData(): Promise<GimleData> {
  const res = await fetch("/api/data");
  if (!res.ok) {
    const body = await res.json().catch(() => ({}) as { error?: string });
    throw new Error(body.error || `Failed to load requirements data (HTTP ${res.status})`);
  }
  return res.json();
}
