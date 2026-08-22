import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useEffect, useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ErrorBanner } from "@/components/vault/ErrorBanner";
import { FafnirMark } from "@/components/vault/FafnirMark";
import { APP_VERSION } from "@/lib/version";
import { useAuthStore } from "@/stores/useAuthStore";

export const Route = createFileRoute("/login")({
  head: () => ({
    meta: [
      { title: "Log in — Gimlé Fafnir Vault" },
      {
        name: "description",
        content: "Authenticate to the Fafnir secrets vault operator console.",
      },
      { property: "og:title", content: "Log in — Gimlé Fafnir Vault" },
      {
        property: "og:description",
        content: "Authenticate to the Fafnir secrets vault operator console.",
      },
    ],
  }),
  component: LoginPage,
});

function LoginPage() {
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const principal = useAuthStore((s) => s.principal);
  const bootstrapped = useAuthStore((s) => s.bootstrapped);
  const pending = useAuthStore((s) => s.pending);
  const error = useAuthStore((s) => s.error);
  const login = useAuthStore((s) => s.login);
  const checkSession = useAuthStore((s) => s.checkSession);

  useEffect(() => {
    if (!bootstrapped) void checkSession();
  }, [bootstrapped, checkSession]);

  // Deliberately not just `if (principal)`: in plaintext mode, /auth/session already reports a
  // synthetic anonymous principal before any real login (see FafnirServer#handleAuthSession and
  // the Principal.anonymous field), so a bare non-null check can't distinguish "there's a free
  // pass, no real identity" from "an operator actually signed in" -- only principal.anonymous can.
  useEffect(() => {
    if (principal && !principal.anonymous) void navigate({ to: "/" });
  }, [principal, navigate]);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    const ok = await login(username, password);
    if (ok) void navigate({ to: "/" });
  };

  return (
    <main className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="w-full max-w-sm">
        <div className="hud-panel p-6">
          <div className="flex flex-col items-center text-center">
            <FafnirMark className="size-20" />
            <h1 className="mt-3 text-sm font-semibold tracking-tight">Gimlé Fafnir Vault</h1>
            <div className="hud-label mt-1">secrets vault</div>
          </div>

          <form className="mt-6 space-y-4" onSubmit={onSubmit}>
            <div className="space-y-1.5">
              <Label htmlFor="username" className="hud-label">
                Username
              </Label>
              <Input
                id="username"
                autoComplete="username"
                className="font-mono text-sm"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                required
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="password" className="hud-label">
                Password
              </Label>
              <Input
                id="password"
                type="password"
                autoComplete="current-password"
                className="font-mono text-sm"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </div>

            {error && <ErrorBanner message={error} />}

            <Button type="submit" className="w-full text-xs" disabled={pending}>
              {pending ? "Authenticating…" : "Log in"}
            </Button>
          </form>
        </div>

        <p className="hud-label mt-4 text-center">gimle // fafnir vault v{APP_VERSION}</p>
      </div>
    </main>
  );
}
