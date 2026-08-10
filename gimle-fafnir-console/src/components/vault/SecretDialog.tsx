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

export interface SecretDialogState {
  open: boolean;
  key: string | null;
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
  onSubmit: (key: string, value: string) => void;
}) {
  const editing = state.key !== null;
  const [keyName, setKeyName] = useState("");
  const [value, setValue] = useState("");

  useEffect(() => {
    if (state.open) {
      setKeyName(state.key ?? "");
      setValue("");
    }
  }, [state.open, state.key]);

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
              className="font-mono text-sm"
              value={keyName}
              disabled={editing}
              placeholder="db/password"
              onChange={(e) => setKeyName(e.target.value)}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="secret-value" className="hud-label">
              Value
            </Label>
            <Textarea
              id="secret-value"
              className="min-h-28 font-mono text-sm"
              value={value}
              onChange={(e) => setValue(e.target.value)}
            />
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            disabled={pending || !keyName.trim() || value.length === 0}
            onClick={() => onSubmit(keyName.trim(), value)}
          >
            {pending ? "Writing…" : editing ? "Save new version" : "Create secret"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
