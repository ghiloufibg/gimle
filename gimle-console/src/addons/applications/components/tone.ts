import type { HealthStatus, SyncStatus } from "@/addons/applications/model";

/** Health and sync tones, kept apart from the components that use them so a hot reload of one
 * never has to re-evaluate the other. */

export const HEALTH_TONE: Record<HealthStatus, string> = {
  Healthy: "text-status-ok",
  Progressing: "text-status-warn",
  Degraded: "text-status-bad",
  Unknown: "text-muted-foreground",
};

export const SYNC_TONE: Record<SyncStatus, string> = {
  Synced: "text-status-ok",
  OutOfSync: "text-status-warn",
  Unknown: "text-muted-foreground",
};

/** The left rail on a tile, and the border on a card that needs the same signal. */
export const HEALTH_RAIL: Record<HealthStatus, string> = {
  Healthy: "border-l-status-ok",
  Progressing: "border-l-status-warn",
  Degraded: "border-l-status-bad",
  Unknown: "border-l-status-muted",
};

/** Stroke colour for a tree connector, as a CSS custom-property value. */
export const HEALTH_STROKE: Record<HealthStatus, string> = {
  Healthy: "var(--status-ok)",
  Progressing: "var(--status-warn)",
  Degraded: "var(--status-bad)",
  Unknown: "var(--status-muted)",
};

export function healthTone(health: HealthStatus): string {
  return HEALTH_TONE[health];
}
