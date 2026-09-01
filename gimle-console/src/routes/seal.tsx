import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";

import { PageContainer, PageHeader, Panel, StatTile } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { useSealStore } from "@/stores/useSealStore";
import { cn } from "@/lib/utils";
import { Copy, KeyRound, RefreshCw, ShieldAlert } from "lucide-react";
import { toast } from "sonner";

const DESCRIPTION =
  "Fafnir's asymmetric sealing key: fetch the public key, rotate it, retire an old one.";

/** The lowest sealing key id, always regenerated if absent, so retiring it is refused outright. */
const BASE_SEALING_KEY_ID = 0;

/** Sealing key ids travel the wire as a single unsigned byte. */
const MAX_SEALING_KEY_ID = 255;

export interface RetireTargetCheck {
  /** The id to send, or null when `error` explains why nothing may be sent. */
  keyId: number | null;
  error: string | null;
}

/**
 * Mirrors every rejection Fafnir's own retire path makes, so a hopeless id is refused here with a
 * specific reason rather than round-tripping to a bare 400. The active-key rule is the one that
 * needs the currently-loaded key: it cannot be checked at all before the public key has loaded, so
 * an unknown active id deliberately lets the id through for the server to rule on.
 */
export function checkRetireTarget(raw: string, activeKeyId: number | null): RetireTargetCheck {
  const trimmed = raw.trim();
  if (trimmed === "") {
    return { keyId: null, error: "Enter the id of the sealing key to retire." };
  }
  if (!/^\d+$/.test(trimmed)) {
    return { keyId: null, error: "A sealing key id is a whole number." };
  }
  const keyId = Number(trimmed);
  if (keyId > MAX_SEALING_KEY_ID) {
    return { keyId: null, error: `A sealing key id is between 0 and ${MAX_SEALING_KEY_ID}.` };
  }
  if (keyId === BASE_SEALING_KEY_ID) {
    return {
      keyId: null,
      error: "Key 0 is the base sealing key and can never be retired — it would regenerate.",
    };
  }
  if (activeKeyId !== null && keyId === activeKeyId) {
    return {
      keyId: null,
      error: `Key ${keyId} is the active sealing key. Rotate first, then retire it.`,
    };
  }
  return { keyId, error: null };
}

/**
 * The typed-confirmation gate on retirement: an operator has to write the id out again, so the
 * destructive action can never ride on one mis-aimed click the way rotation safely can.
 */
export function retirementConfirmed(typed: string, keyId: number): boolean {
  return typed.trim() === String(keyId);
}

export const Route = createFileRoute("/seal")({
  head: () => ({
    meta: [
      { title: "Seal Keys — Gimlé Console" },
      { name: "description", content: DESCRIPTION },
      { property: "og:title", content: "Seal Keys — Gimlé Console" },
      { property: "og:description", content: DESCRIPTION },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: SealPage,
});

function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="mb-3 rounded border border-status-bad/40 bg-status-bad-bg/40 px-3 py-2 text-xs text-status-bad">
      {message}
    </div>
  );
}

function SealPage() {
  const activeKey = useSealStore((s) => s.activeKey);
  const loading = useSealStore((s) => s.loading);
  const error = useSealStore((s) => s.error);
  const load = useSealStore((s) => s.load);
  const rotate = useSealStore((s) => s.rotate);
  const retire = useSealStore((s) => s.retire);

  const [retireInput, setRetireInput] = useState("");
  const [confirmInput, setConfirmInput] = useState("");
  const [confirmOpen, setConfirmOpen] = useState(false);

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const activeKeyId = activeKey?.sealingKeyId ?? null;
  const target = checkRetireTarget(retireInput, activeKeyId);
  const confirmed = target.keyId !== null && retirementConfirmed(confirmInput, target.keyId);

  async function copyPublicKey() {
    if (!activeKey) return;
    try {
      await navigator.clipboard.writeText(activeKey.publicKey);
      toast.success("Public key copied");
    } catch {
      // Clipboard access is denied outside a secure context, which is a normal way to serve a
      // local control plane -- the key is selectable in the block either way.
      toast.error("Clipboard unavailable");
    }
  }

  async function handleRotate() {
    try {
      const newId = await rotate();
      toast.success(`Sealing key rotated — key ${newId} is now active`);
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  function openConfirm() {
    setConfirmInput("");
    setConfirmOpen(true);
  }

  async function handleRetire() {
    if (target.keyId === null) return;
    try {
      const retired = await retire(target.keyId);
      setRetireInput("");
      toast.success(`Sealing key ${retired} retired — its private key is gone`);
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  return (
    <PageContainer>
      <PageHeader
        title="Seal Keys"
        subtitle="Fafnir's asymmetric sealing key pair. Its public half is what seals a value offline, with no live session; only Fafnir's private half can ever unwrap one."
        actions={
          <Button size="sm" variant="outline" onClick={() => load()} disabled={loading}>
            <RefreshCw className={cn("mr-1.5 h-3 w-3", loading && "animate-spin")} />
            Refresh
          </Button>
        }
      />

      {error && <ErrorBanner message={error} />}

      <Panel
        title="Active sealing key"
        className="mb-4"
        aside={
          <Button size="sm" variant="outline" onClick={handleRotate} disabled={loading}>
            <KeyRound className="mr-1.5 h-3 w-3" />
            Rotate sealing key
          </Button>
        }
      >
        {!activeKey ? (
          <div className="px-4 py-10 text-center text-xs text-muted-foreground">
            {loading ? "Loading…" : "No sealing key loaded."}
          </div>
        ) : (
          <div className="p-4">
            <div className="mb-4 grid gap-3 sm:grid-cols-2">
              <StatTile label="Active key id" value={activeKey.sealingKeyId} tone="primary" />
              <StatTile label="Algorithm" value={activeKey.algorithm} tone="muted" />
            </div>

            <div className="mb-1 flex items-center justify-between gap-2">
              <span className="hud-label">Public key · base64 X.509 SubjectPublicKeyInfo</span>
              <button
                onClick={copyPublicKey}
                className="inline-flex items-center gap-1 text-[10px] uppercase tracking-wider text-muted-foreground hover:text-foreground"
                aria-label="Copy public key"
                title="Copy the full base64 public key"
              >
                <Copy className="h-3 w-3" />
                Copy
              </button>
            </div>
            <pre className="max-h-48 overflow-y-auto whitespace-pre-wrap break-all rounded border border-border bg-muted/40 p-3 font-mono text-[11px] leading-relaxed">
              {activeKey.publicKey}
            </pre>
            <p className="mt-2 text-xs text-muted-foreground">
              Save this to a file and pass it to{" "}
              <code className="font-mono">gimle seal value --public-key</code> to seal a value
              entirely client-side. Rotation mints a new id and leaves earlier keys on the ring, so
              an envelope sealed under an older id still unwraps.
            </p>
          </div>
        )}
      </Panel>

      <Panel title="Retire a sealing key" className="border-status-bad/40">
        <div className="p-4">
          <div className="mb-3 flex gap-2 rounded border border-status-bad/40 bg-status-bad-bg/40 p-3 text-xs text-status-bad">
            <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
            <p>
              Retiring deletes that key's private half from Fafnir's key ring for good. Every sealed
              envelope produced under it that has not already been committed becomes permanently
              unreadable — by anyone, including Fafnir. There is no undo and no recovery. A
              SecretMap value already applied through{" "}
              <code className="font-mono">secretmap seal</code> was re-encrypted under Fafnir's own
              symmetric key at commit time and is unaffected.
            </p>
          </div>

          <div className="flex flex-wrap items-end gap-2">
            <div className="grid gap-1">
              <Label
                htmlFor="retire-key-id"
                className="text-[10px] uppercase tracking-wider text-muted-foreground"
              >
                Key id to retire
              </Label>
              <Input
                id="retire-key-id"
                className="h-8 w-32 font-mono text-xs"
                value={retireInput}
                onChange={(e) => setRetireInput(e.target.value)}
                placeholder="e.g. 2"
                inputMode="numeric"
              />
            </div>
            <Button
              size="sm"
              variant="destructive"
              onClick={openConfirm}
              disabled={target.keyId === null}
            >
              Retire key…
            </Button>
            {retireInput.trim() !== "" && target.error && (
              <p className="text-xs text-status-bad">{target.error}</p>
            )}
          </div>

          <p className="mt-3 text-xs text-muted-foreground">
            Fafnir serves only the currently active key — it publishes no listing of the ids still
            on the ring, so the id to retire comes from your own record of past rotations.
          </p>
        </div>
      </Panel>

      <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Retire sealing key {target.keyId}?</AlertDialogTitle>
            <AlertDialogDescription>
              This permanently destroys sealing key {target.keyId}'s private half. Any value sealed
              under it and not yet committed can never be decrypted again, by anyone. This cannot be
              undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <div className="grid gap-1">
            <Label
              htmlFor="retire-confirm"
              className="text-[10px] uppercase tracking-wider text-muted-foreground"
            >
              Type {target.keyId} to confirm
            </Label>
            <Input
              id="retire-confirm"
              className="h-8 w-32 font-mono text-xs"
              value={confirmInput}
              onChange={(e) => setConfirmInput(e.target.value)}
              inputMode="numeric"
              autoComplete="off"
            />
          </div>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleRetire}
              disabled={!confirmed}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              Retire permanently
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </PageContainer>
  );
}
