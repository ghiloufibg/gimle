import type { ReactNode } from "react";

import { PageContainer, PageHeader } from "@/components/page-shell";
import { useAddonsStore } from "@/stores/useAddonsStore";
import type { Addon } from "@/addons";

/**
 * Gates an addon's screen on the control plane advertising it.
 *
 * Deliberately not a 404: the route genuinely exists in this bundle, and a link someone shared
 * should say why it is not showing rather than look broken. It names the property that turns the
 * screen on, so the reader can act without going to find the docs.
 */
export function AddonRoute({ addon, children }: { addon: Addon; children: ReactNode }) {
  const initialized = useAddonsStore((s) => s.initialized);
  const enabled = useAddonsStore((s) => s.enabledIds.includes(addon.id));

  // Nothing is rendered before the one /console/addons read answers -- flashing the screen and
  // then replacing it with "not enabled" reads as a failure that just occurred.
  if (!initialized) return null;
  if (enabled) return <>{children}</>;

  return (
    <PageContainer>
      <PageHeader eyebrow="Gimlé // Addon" title={addon.title} subtitle={addon.description} />
      <div className="rounded border border-border bg-muted/40 px-4 py-3 text-xs text-muted-foreground">
        <p className="mb-2">
          <span className="font-medium text-foreground">{addon.title}</span> is not enabled on this
          control plane.
        </p>
        <p>
          Start it with{" "}
          <code className="rounded-sm bg-card px-1 py-0.5 font-mono text-[11px] text-foreground">
            -Dgimle.controlplane.consoleAddons={addon.id}
          </code>{" "}
          (comma-separated for several; omit the property to advertise every bundled addon).
        </p>
      </div>
    </PageContainer>
  );
}
