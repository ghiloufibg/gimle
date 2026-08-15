import { useEffect } from "react";
import { createFileRoute, useNavigate } from "@tanstack/react-router";

import { AppShell } from "@/components/AppShell";
import { AndvariMark } from "@/components/AndvariMark";
import { setUnauthorizedHandler } from "@/repositories/http/apiClient";
import { useAuthStore } from "@/stores/authStore";

export const Route = createFileRoute("/_shell")({
  component: ShellRoute,
});

function ShellRoute() {
  const navigate = useNavigate();
  const bootstrapped = useAuthStore((s) => s.bootstrapped);
  const principal = useAuthStore((s) => s.principal);
  const checkSession = useAuthStore((s) => s.checkSession);
  const clearPrincipal = useAuthStore((s) => s.clearPrincipal);

  useEffect(() => {
    if (!useAuthStore.getState().bootstrapped) void checkSession();
  }, [checkSession]);

  useEffect(() => {
    setUnauthorizedHandler(() => {
      clearPrincipal();
      void navigate({ to: "/login" });
    });
    return () => setUnauthorizedHandler(null);
  }, [clearPrincipal, navigate]);

  useEffect(() => {
    if (bootstrapped && !principal) void navigate({ to: "/login" });
  }, [bootstrapped, principal, navigate]);

  if (!bootstrapped || !principal) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-3 bg-background">
        <AndvariMark className="h-12 w-12 animate-pulse" />
        <p className="hud-label">establishing session…</p>
      </div>
    );
  }

  return <AppShell />;
}
