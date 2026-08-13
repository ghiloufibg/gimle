import { cn } from "@/lib/utils";
import { useDisplayStore } from "@/stores/useDisplayStore";

export function PageHeader({
  title,
  subtitle,
  eyebrow,
  actions,
  className,
}: {
  title: React.ReactNode;
  subtitle?: React.ReactNode;
  eyebrow?: React.ReactNode;
  actions?: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex items-end justify-between gap-4 mb-6 border-b border-primary/20 pb-4",
        className,
      )}
    >
      <div>
        <div className="hud-label mb-1">{eyebrow ?? "Gimlé // Console"}</div>
        <h1 className="text-2xl md:text-3xl font-light tracking-tight text-foreground">{title}</h1>
        {subtitle && <p className="text-xs text-muted-foreground mt-1">{subtitle}</p>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}

export function PageContainer({ children }: { children: React.ReactNode }) {
  const density = useDisplayStore((s) => s.density);
  return (
    <div
      className={cn("max-w-[1600px] mx-auto", density === "roomy" ? "p-6 md:p-10" : "p-4 md:p-6")}
    >
      {children}
    </div>
  );
}

/** HUD stat tile: mono numerals, left signal rail, optional alarm treatment. */
export function StatTile({
  label,
  value,
  note,
  tone = "default",
}: {
  label: string;
  value: React.ReactNode;
  note?: React.ReactNode;
  tone?: "primary" | "default" | "muted" | "alarm";
}) {
  return (
    <div
      className={cn(
        "p-4 border-l-2 transition-colors",
        tone === "primary" && "bg-primary/5 border-primary",
        tone === "default" && "bg-primary/5 border-primary/40",
        tone === "muted" && "bg-muted/40 border-status-muted",
        tone === "alarm" && "bg-status-bad-bg/40 border-status-bad",
      )}
    >
      <p className={cn("hud-label mb-1", tone === "alarm" && "text-status-bad")}>{label}</p>
      <div className="flex items-baseline gap-2 flex-wrap">
        <span
          className={cn(
            "text-3xl font-bold font-mono tabular-nums",
            tone === "alarm"
              ? "text-status-bad"
              : tone === "muted"
                ? "text-muted-foreground"
                : "text-signal",
          )}
        >
          {value}
        </span>
        {note}
      </div>
    </div>
  );
}

/** Framed HUD panel with a mono header strip. */
export function Panel({
  title,
  aside,
  children,
  className,
}: {
  title: string;
  aside?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("hud-panel rounded-sm overflow-hidden", className)}>
      <div className="bg-primary/10 px-4 py-2 border-b border-primary/10 flex items-center justify-between gap-2">
        <h3 className="hud-label text-primary">{title}</h3>
        {aside}
      </div>
      {children}
    </div>
  );
}
