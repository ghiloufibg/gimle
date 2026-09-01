import { useEffect } from "react";
import type { Permission, ResourceKind, Verb } from "@/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { optionsIncludingSelected } from "@/lib/authz-vocabulary";
import { useAuthzVocabularyStore } from "@/stores/useAuthzVocabularyStore";
import { Plus, X } from "lucide-react";

/**
 * Repeatable permission row editor used by the Roles tab. Both pickers are filled from the control
 * plane's own `ResourceKind`/`Verb` enums (`GET /authz/vocabulary`) rather than a list compiled into
 * this console, so a kind the platform has grown is grantable here the moment the server knows it.
 */
export function PermissionRows({
  value,
  onChange,
}: {
  value: Permission[];
  onChange: (next: Permission[]) => void;
}) {
  const resourceKinds = useAuthzVocabularyStore((s) => s.resourceKinds);
  const verbs = useAuthzVocabularyStore((s) => s.verbs);
  const loadVocabulary = useAuthzVocabularyStore((s) => s.load);

  useEffect(() => {
    loadVocabulary();
  }, [loadVocabulary]);

  function patch(i: number, p: Partial<Permission>) {
    onChange(value.map((row, ix) => (ix === i ? { ...row, ...p } : row)));
  }
  function addRow() {
    onChange([...value, { resource: "DEPLOYMENT", verb: "READ" }]);
  }
  function removeRow(i: number) {
    onChange(value.filter((_, ix) => ix !== i));
  }

  return (
    <div className="grid gap-2">
      <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
        Permissions
      </Label>
      {value.length === 0 && <p className="text-xs text-muted-foreground">No permissions yet.</p>}
      {value.map((row, i) => (
        <div key={i} className="flex flex-wrap items-center gap-2">
          <Select
            value={row.resource}
            onValueChange={(v) => patch(i, { resource: v as ResourceKind })}
          >
            <SelectTrigger className="h-8 w-52 font-mono text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {optionsIncludingSelected(resourceKinds, row.resource).map((r) => (
                <SelectItem key={r} value={r} className="font-mono text-xs">
                  {r}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Select value={row.verb} onValueChange={(v) => patch(i, { verb: v as Verb })}>
            <SelectTrigger className="h-8 w-32 font-mono text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {optionsIncludingSelected(verbs, row.verb).map((v) => (
                <SelectItem key={v} value={v} className="font-mono text-xs">
                  {v}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Input
            className="h-8 w-48 font-mono text-xs"
            placeholder="tenant scope (optional)"
            value={row.tenantScope ?? ""}
            onChange={(e) => patch(i, { tenantScope: e.target.value || undefined })}
          />
          <button
            type="button"
            onClick={() => removeRow(i)}
            className="text-muted-foreground hover:text-status-bad"
            aria-label="Remove permission"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      ))}
      <div>
        <Button type="button" variant="outline" size="sm" onClick={addRow}>
          <Plus className="h-3.5 w-3.5" />
          Add permission
        </Button>
      </div>
    </div>
  );
}
