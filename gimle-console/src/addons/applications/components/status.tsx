import {
  CircleArrowUp,
  CircleCheck,
  CircleDashed,
  HeartCrack,
  HeartPulse,
  LoaderCircle,
} from "lucide-react";

import { cn } from "@/lib/utils";
import { HEALTH_TONE, SYNC_TONE } from "@/addons/applications/components/tone";
import type { HealthStatus, SyncStatus } from "@/addons/applications/model";

/**
 * The two verdicts, drawn so they read apart at a glance: health is a heartbeat, sync an arrow into
 * a circle. Colour alone would not tell them apart on a wall of tiles, and colour alone is not
 * something every operator can rely on.
 */

export function HealthLabel({ health, className }: { health: HealthStatus; className?: string }) {
  const Icon =
    health === "Healthy"
      ? HeartPulse
      : health === "Degraded"
        ? HeartCrack
        : health === "Progressing"
          ? LoaderCircle
          : CircleDashed;
  return (
    <span className={cn("inline-flex items-center gap-1.5", HEALTH_TONE[health], className)}>
      <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden />
      <span className="font-medium">{health}</span>
    </span>
  );
}

export function SyncLabel({ sync, className }: { sync: SyncStatus; className?: string }) {
  const Icon =
    sync === "Synced" ? CircleCheck : sync === "OutOfSync" ? CircleArrowUp : CircleDashed;
  return (
    <span className={cn("inline-flex items-center gap-1.5", SYNC_TONE[sync], className)}>
      <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden />
      <span className="font-medium">{sync}</span>
    </span>
  );
}
