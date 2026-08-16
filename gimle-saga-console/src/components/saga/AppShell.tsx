import { Link } from "@tanstack/react-router";
import { Activity, GitCompareArrows, ListOrdered, Moon, Radar, Sun } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
import { useThemeStore } from "@/stores/themeStore";
import { SagaEmblem } from "./SagaEmblem";
import { HudLabel } from "./primitives";

const NAV = [
  { to: "/", label: "Runs", icon: ListOrdered, exact: true },
  { to: "/gjallarhorn", label: "Gjallarhorn", icon: Radar },
  { to: "/compare", label: "Compare", icon: GitCompareArrows },
] as const;

export function AppShell({ children }: { children: ReactNode }) {
  const theme = useThemeStore((s) => s.theme);
  const toggle = useThemeStore((s) => s.toggle);

  return (
    <div className="flex min-h-screen bg-background">
      <aside className="sticky top-0 flex h-screen w-56 shrink-0 flex-col border-r border-sidebar-border bg-sidebar">
        <div className="flex items-center gap-2 border-b border-sidebar-border px-4 py-4">
          <SagaEmblem size={28} className="text-primary" />
          <span className="font-mono text-[17px] leading-none font-bold tracking-[0.34em] text-foreground">
            SAGA
          </span>
        </div>

        <div className="px-4 pt-4">
          <HudLabel>gimlé console</HudLabel>
        </div>

        <nav className="mt-2 flex flex-col gap-0.5 px-2">
          {NAV.map(({ to, label, icon: Icon, ...rest }) => (
            <Link
              key={to}
              to={to}
              activeOptions={{ exact: "exact" in rest ? rest.exact : false }}
              className="flex items-center gap-2 rounded-sm px-2 py-1.5 font-mono text-[11px] font-medium tracking-[0.1em] uppercase text-muted-foreground transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
              activeProps={{
                className:
                  "bg-sidebar-accent text-sidebar-accent-foreground border-l-2 border-primary",
              }}
            >
              <Icon className="h-3.5 w-3.5" strokeWidth={2} />
              {label}
            </Link>
          ))}
        </nav>

        <div className="mt-auto border-t border-sidebar-border px-4 py-3">
          <div className="flex items-center justify-between">
            <div>
              <HudLabel>stream</HudLabel>
              <div className="mt-1 flex items-center gap-1.5">
                <Activity className="h-3 w-3 text-signal" strokeWidth={2.4} />
                <span className="num text-[11px] text-muted-foreground">mock feed</span>
              </div>
            </div>
            <button
              type="button"
              onClick={toggle}
              aria-label="Toggle theme"
              className="rounded-sm border border-sidebar-border p-1.5 text-muted-foreground transition-colors hover:bg-sidebar-accent hover:text-foreground"
            >
              {theme === "dark" ? (
                <Sun className="h-3.5 w-3.5" />
              ) : (
                <Moon className="h-3.5 w-3.5" />
              )}
            </button>
          </div>
        </div>
      </aside>

      <main className="min-w-0 flex-1">{children}</main>
    </div>
  );
}

export function PageHeader({
  eyebrow,
  title,
  right,
  children,
  className,
}: {
  eyebrow: string;
  title: ReactNode;
  right?: ReactNode;
  children?: ReactNode;
  className?: string;
}) {
  return (
    <header className={cn("border-b border-border bg-card/60 px-6 py-4", className)}>
      <div className="flex items-start justify-between gap-6">
        <div className="min-w-0">
          <HudLabel>{eyebrow}</HudLabel>
          <h1 className="mt-1 truncate text-xl font-semibold text-foreground">{title}</h1>
        </div>
        {right}
      </div>
      {children}
    </header>
  );
}
