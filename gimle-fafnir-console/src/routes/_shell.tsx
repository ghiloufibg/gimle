import { Outlet, createFileRoute, useNavigate } from "@tanstack/react-router";
import { useEffect } from "react";

import { SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { AppSidebar } from "@/components/vault/AppSidebar";
import { StatusPill } from "@/components/vault/StatusPill";
import { useAuthStore } from "@/stores/useAuthStore";

export const Route = createFileRoute("/_shell")({
  component: ShellLayout,
});

function ShellLayout() {
  const navigate = useNavigate();
  const principal = useAuthStore((s) => s.principal);
  const bootstrapped = useAuthStore((s) => s.bootstrapped);
  const checkSession = useAuthStore((s) => s.checkSession);

  useEffect(() => {
    if (!bootstrapped) void checkSession();
  }, [bootstrapped, checkSession]);

  useEffect(() => {
    if (bootstrapped && !principal) void navigate({ to: "/login" });
  }, [bootstrapped, principal, navigate]);

  if (!bootstrapped || !principal) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <span className="hud-label">establishing session…</span>
      </div>
    );
  }

  return (
    <SidebarProvider>
      <div className="flex min-h-screen w-full">
        <AppSidebar />
        <div className="flex flex-1 flex-col">
          <header className="flex h-11 items-center justify-between border-b bg-card/60 px-3">
            <div className="flex items-center gap-2">
              <SidebarTrigger className="size-7" />
              <span className="hud-label">fafnir // vault console</span>
            </div>
            <StatusPill />
          </header>
          <main className="flex-1 p-4 md:p-6">
            <Outlet />
          </main>
        </div>
      </div>
    </SidebarProvider>
  );
}
