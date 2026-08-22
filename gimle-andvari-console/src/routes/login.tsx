import { useEffect, useState } from "react";
import { createFileRoute, useNavigate } from "@tanstack/react-router";

import { AndvariMark } from "@/components/AndvariMark";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { APP_VERSION } from "@/lib/version";
import { useAuthStore } from "@/stores/authStore";

export const Route = createFileRoute("/login")({
  head: () => ({
    meta: [
      { title: "Log in — Gimlé Andvari" },
      { name: "description", content: "Authenticate to the Gimlé Andvari operator console." },
    ],
  }),
  component: LoginPage,
});

function LoginPage() {
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const pending = useAuthStore((s) => s.pending);
  const error = useAuthStore((s) => s.error);
  const principal = useAuthStore((s) => s.principal);
  const login = useAuthStore((s) => s.login);

  useEffect(() => {
    if (principal) void navigate({ to: "/" });
  }, [principal, navigate]);

  const onSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    const ok = await login(username, password);
    if (ok) await navigate({ to: "/" });
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="w-full max-w-sm">
        <form onSubmit={onSubmit} className="hud-panel rounded-sm p-8">
          <AndvariMark className="mx-auto h-20 w-20" />
          <h1 className="mt-4 text-center text-sm font-semibold tracking-tight">Gimlé Andvari</h1>
          <p className="hud-label mt-1.5 text-center">module artifact registry</p>

          {error ? (
            <p className="mt-5 rounded-sm border border-status-bad/30 bg-status-bad-bg px-3 py-2 text-xs text-status-bad">
              {error}
            </p>
          ) : null}

          <div className="mt-6 space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="username" className="hud-label">
                username
              </Label>
              <Input
                id="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                autoComplete="username"
                className="rounded-sm font-mono text-sm"
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="password" className="hud-label">
                password
              </Label>
              <Input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
                className="rounded-sm font-mono text-sm"
              />
            </div>
          </div>

          <Button type="submit" disabled={pending} className="mt-6 w-full rounded-sm text-xs">
            {pending ? "Authenticating…" : "Log in"}
          </Button>
        </form>
        <p className="hud-label mt-4 text-center">gimle // andvari registry v{APP_VERSION}</p>
      </div>
    </div>
  );
}
