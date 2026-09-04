import { useEffect, useState } from "react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { pemProblem, SECRET_TYPES } from "@/lib/secretType";
import type { SecretType } from "@/types";

export interface SecretDialogState {
  open: boolean;
  key: string | null;
  // The secret's currently-declared type, resolved by the caller before opening the dialog for an
  // existing key -- null for a brand-new secret, or while that lookup is still in flight.
  currentType?: SecretType | null;
}

export function SecretDialog({
  state,
  tenantId,
  pending,
  onOpenChange,
  onSubmit,
}: {
  state: SecretDialogState;
  tenantId: string;
  pending: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (key: string, value: string, type: SecretType) => void;
}) {
  const editing = state.key !== null;
  const [keyName, setKeyName] = useState("");
  const [value, setValue] = useState("");
  const [type, setType] = useState<SecretType>("opaque");

  useEffect(() => {
    if (state.open) {
      setKeyName(state.key ?? "");
      setValue("");
      // Defaults to the secret's own declared type on every new-version write -- the type field
      // used to not exist here at all, so a write through this dialog silently reset any typed
      // secret (pem-certificate, pem-private-key) back to opaque with no indication anything
      // changed. Defaulting to the current type, rather than always to opaque, preserves it unless
      // an operator deliberately picks a different one.
      setType(state.currentType ?? "opaque");
    }
  }, [state.open, state.key, state.currentType]);

  const problem = type === "opaque" ? null : pemProblem(type, value);
  const canSubmit = !pending && keyName.trim().length > 0 && value.length > 0 && !problem;

  return (
    <Dialog open={state.open} onOpenChange={onOpenChange}>
      <DialogContent className="rounded-sm sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{editing ? "New version" : "New secret"}</DialogTitle>
          <DialogDescription className="font-mono text-xs">
            tenant: {tenantId || "—"} · every write creates a new version
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-3">
          <div className="space-y-1.5">
            <Label htmlFor="secret-key" className="hud-label">
              Key
            </Label>
            <Input
              id="secret-key"
              className="font-mono text-xs"
              value={keyName}
              disabled={editing}
              placeholder="db/password"
              onChange={(e) => setKeyName(e.target.value)}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="secret-type" className="hud-label">
              Type
            </Label>
            <select
              id="secret-type"
              className="h-8 w-full border border-border bg-background px-2 font-mono text-xs"
              value={type}
              onChange={(e) => setType(e.target.value as SecretType)}
            >
              {SECRET_TYPES.map((candidate) => (
                <option key={candidate} value={candidate}>
                  {candidate}
                </option>
              ))}
            </select>
            {editing && state.currentType && (
              <p className="text-[11px] text-muted-foreground">
                currently declared as <span className="font-mono">{state.currentType}</span>
              </p>
            )}
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="secret-value" className="hud-label">
              Value
            </Label>
            <Textarea
              id="secret-value"
              className="min-h-28 font-mono text-xs"
              value={value}
              onChange={(e) => setValue(e.target.value)}
            />
            {problem && (
              <p className="font-mono text-[11px] text-status-bad">
                {type}: {problem}
              </p>
            )}
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button disabled={!canSubmit} onClick={() => onSubmit(keyName.trim(), value, type)}>
            {pending ? "Writing…" : editing ? "Save new version" : "Create secret"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
