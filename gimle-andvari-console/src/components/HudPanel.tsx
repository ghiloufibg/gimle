import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

export function HudPanel({
  label,
  children,
  className,
  actions,
}: {
  label?: string;
  children: ReactNode;
  className?: string;
  actions?: ReactNode;
}) {
  return (
    <section className={cn("hud-panel rounded-sm", className)}>
      {label || actions ? (
        <div className="flex items-center justify-between gap-3 border-b border-border/60 px-4 py-2.5">
          <p className="hud-label">{label}</p>
          {actions}
        </div>
      ) : null}
      <div className="p-4">{children}</div>
    </section>
  );
}
