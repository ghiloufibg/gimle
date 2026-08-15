import { useEffect, useState } from "react";
import { Link, Outlet, useNavigate, useRouterState } from "@tanstack/react-router";
import { Boxes, LayoutDashboard, LogOut, Moon, Package, Sun } from "lucide-react";

import { AndvariMark } from "@/components/AndvariMark";
import { Button } from "@/components/ui/button";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarTrigger,
} from "@/components/ui/sidebar";
import { applyTheme, resolveTheme, type ThemeMode } from "@/lib/theme";
import { cn } from "@/lib/utils";
import { useAuthStore } from "@/stores/authStore";
import { useStatusStore } from "@/stores/statusStore";

const NAV = [
  { to: "/", label: "Overview", icon: LayoutDashboard },
  { to: "/artifacts", label: "Artifacts", icon: Package },
  { to: "/repository", label: "Repository", icon: Boxes },
] as const;

function ThemeToggle() {
  const [mode, setMode] = useState<ThemeMode>("light");

  useEffect(() => {
    setMode(resolveTheme());
  }, []);

  const toggle = () => {
    const next: ThemeMode = mode === "dark" ? "light" : "dark";
    setMode(next);
    applyTheme(next);
  };

  return (
    <Button
      variant="ghost"
      size="icon"
      className="h-7 w-7 rounded-sm"
      aria-label="Toggle theme"
      onClick={toggle}
    >
      {mode === "dark" ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
    </Button>
  );
}

function StatusPill() {
  const reachable = useStatusStore((s) => s.reachable);
  const loading = useStatusStore((s) => s.loading);
  const status = useStatusStore((s) => s.status);

  const tone = loading && !status ? "muted" : reachable ? "ok" : "bad";
  const text = loading && !status ? "probing" : reachable ? "reachable" : "unreachable";

  return (
    <span
      className={cn(
        "flex items-center gap-2 rounded-sm border px-2.5 py-1 font-mono text-[10px] font-bold tracking-[0.18em] uppercase",
        tone === "ok" && "border-status-ok/30 bg-status-ok-bg text-status-ok",
        tone === "bad" && "border-status-bad/30 bg-status-bad-bg text-status-bad",
        tone === "muted" && "border-border bg-muted text-status-muted",
      )}
    >
      <span
        className={cn(
          "h-1.5 w-1.5 rounded-full",
          tone === "ok" && "bg-status-ok",
          tone === "bad" && "bg-status-bad",
          tone === "muted" && "bg-status-muted",
        )}
      />
      {text}
    </span>
  );
}

export function AppShell() {
  const navigate = useNavigate();
  const principal = useAuthStore((s) => s.principal);
  const logout = useAuthStore((s) => s.logout);
  const refreshStatus = useStatusStore((s) => s.refresh);
  const pathname = useRouterState({ select: (s) => s.location.pathname });

  useEffect(() => {
    void refreshStatus();
    const interval = setInterval(() => void refreshStatus(), 30_000);
    return () => clearInterval(interval);
  }, [refreshStatus]);

  const onLogout = async () => {
    await logout();
    await navigate({ to: "/login" });
  };

  return (
    <SidebarProvider>
      <Sidebar collapsible="icon">
        <SidebarHeader className="border-b border-sidebar-border">
          <div className="flex items-center gap-2.5 px-1 py-1.5">
            <AndvariMark className="h-8 w-8 shrink-0" />
            <div className="min-w-0 group-data-[collapsible=icon]:hidden">
              <p className="truncate text-sm font-semibold">Gimlé Andvari</p>
              <p className="hud-label truncate">artifact registry</p>
            </div>
          </div>
        </SidebarHeader>
        <SidebarContent>
          <SidebarGroup>
            <SidebarGroupLabel className="hud-label">console</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                {NAV.map((item) => {
                  const active = item.to === "/" ? pathname === "/" : pathname.startsWith(item.to);
                  return (
                    <SidebarMenuItem key={item.to}>
                      <SidebarMenuButton asChild isActive={active} tooltip={item.label}>
                        <Link to={item.to}>
                          <item.icon />
                          <span>{item.label}</span>
                        </Link>
                      </SidebarMenuButton>
                    </SidebarMenuItem>
                  );
                })}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        </SidebarContent>
        <SidebarFooter className="border-t border-sidebar-border">
          <div className="flex items-center justify-between gap-2 px-1 py-1">
            <div className="min-w-0 group-data-[collapsible=icon]:hidden">
              <p className="hud-label">signed in</p>
              <p className="truncate font-mono text-xs">{principal?.username ?? "—"}</p>
            </div>
            <div className="flex items-center gap-1">
              <ThemeToggle />
              <Button
                variant="ghost"
                size="icon"
                className="h-7 w-7 rounded-sm"
                aria-label="Log out"
                onClick={() => void onLogout()}
              >
                <LogOut className="h-4 w-4" />
              </Button>
            </div>
          </div>
        </SidebarFooter>
      </Sidebar>

      <SidebarInset>
        <header className="flex h-11 shrink-0 items-center gap-3 border-b border-border bg-background/80 px-4 backdrop-blur">
          <SidebarTrigger className="h-7 w-7 rounded-sm" />
          <p className="hud-label">andvari // artifact registry</p>
          <div className="ml-auto">
            <StatusPill />
          </div>
        </header>
        <main className="flex-1 px-4 py-8">
          <div className="mx-auto flex max-w-5xl flex-col gap-6">
            <Outlet />
          </div>
        </main>
      </SidebarInset>
    </SidebarProvider>
  );
}
