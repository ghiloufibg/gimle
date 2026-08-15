import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";

import gimleMark from "@/assets/gimle-alt-badge.png";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useAuthStore } from "@/stores/useAuthStore";

export const Route = createFileRoute("/login")({
  head: () => ({
    meta: [
      { title: "Sign in — Gimlé Console" },
      {
        name: "description",
        content: "Sign in to the Gimlé Console to operate the JVM cluster control plane.",
      },
      { property: "og:title", content: "Sign in — Gimlé Console" },
      {
        property: "og:description",
        content: "Operator sign-in for the Gimlé JVM cluster control plane.",
      },
    ],
  }),
  component: LoginPage,
});

function LoginPage() {
  const navigate = useNavigate();
  const status = useAuthStore((s) => s.status);
  const principal = useAuthStore((s) => s.principal);
  const error = useAuthStore((s) => s.error);
  const login = useAuthStore((s) => s.login);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [pending, setPending] = useState(false);

  // Deliberately not just `status === "authenticated"`: in plaintext mode, /auth/session already
  // reports a synthetic anonymous principal before any real login (see ApiServer#handleAuthSession
  // and the Principal.anonymous field), so status alone can't distinguish "there's a free pass, no
  // real identity" from "an operator actually signed in" -- only principal.anonymous can. Depending
  // on `principal` itself, not just `status`, also matters mechanically: without it, a real login
  // right after that free-pass would leave status at the same "authenticated" string value it
  // already had, so this effect would never re-run at all.
  useEffect(() => {
    if (status === "authenticated" && principal && !principal.anonymous) {
      navigate({ to: "/", replace: true });
    }
  }, [status, principal, navigate]);

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setPending(true);
    const ok = await login(username, password);
    setPending(false);
    // Belt-and-suspenders alongside the effect above: navigating here directly, right after a
    // successful submit, doesn't depend on React ever re-running that effect at all.
    if (ok) navigate({ to: "/", replace: true });
  }

  return (
    <div className="min-h-screen w-full bg-background text-foreground flex items-center justify-center p-6">
      <div className="w-full max-w-sm">
        <div className="flex items-center gap-3 mb-4">
          <img
            src={gimleMark}
            alt="Gimlé"
            className="h-9 w-9 rounded object-contain"
            width={36}
            height={36}
          />
          <div className="leading-tight">
            <div className="text-sm font-semibold tracking-tight">Gimlé Console</div>
            <div className="hud-label">Cluster control plane</div>
          </div>
        </div>

        <div className="hud-panel rounded-sm overflow-hidden">
          <div className="bg-primary/10 px-4 py-2 border-b border-primary/10 flex items-center justify-between gap-2">
            <h1 className="hud-label text-primary">Operator sign-in</h1>
            <span className="hud-label text-muted-foreground">secure</span>
          </div>

          <form onSubmit={onSubmit} className="p-4 space-y-4">
            <div className="space-y-1.5">
              <label htmlFor="username" className="hud-label">
                Username
              </label>
              <Input
                id="username"
                name="username"
                autoComplete="username"
                autoFocus
                required
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                className="font-mono text-sm rounded-sm"
              />
            </div>

            <div className="space-y-1.5">
              <label htmlFor="password" className="hud-label">
                Password
              </label>
              <Input
                id="password"
                name="password"
                type="password"
                autoComplete="current-password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="font-mono text-sm rounded-sm"
              />
            </div>

            {error && (
              <p className="text-xs font-mono text-status-bad border-l-2 border-status-bad bg-status-bad-bg/40 px-2 py-1.5">
                {error}
              </p>
            )}

            <Button
              type="submit"
              disabled={pending}
              className="w-full rounded-sm font-mono uppercase tracking-widest text-xs"
            >
              {pending ? "Authenticating…" : "Log in"}
            </Button>
          </form>
        </div>

        <p className="mt-3 hud-label text-muted-foreground">gimle // auth v{__APP_VERSION__}</p>
      </div>
    </div>
  );
}
