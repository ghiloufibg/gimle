import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { notifyApiError } from "@/lib/api-error";
import { Pencil, Plus, RefreshCw, Trash2, X } from "lucide-react";

import { PageContainer, PageHeader } from "@/components/page-shell";
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
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { useLimitRangesStore } from "@/stores/useLimitRangesStore";
import { useTenantsStore } from "@/stores/useTenantsStore";
import type { LimitRange, ResourceBound } from "@/types";

const DESCRIPTION = "Per-tenant min/max bounds on what any single workload may request or limit.";

export const Route = createFileRoute("/limitranges")({
  head: () => ({
    meta: [
      { title: "LimitRanges — Gimlé Console" },
      { name: "description", content: DESCRIPTION },
      { property: "og:title", content: "LimitRanges — Gimlé Console" },
      { property: "og:description", content: DESCRIPTION },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: LimitRangesPage,
});

/* ----------------------------- pure form logic ----------------------------- */

export const BOUND_KEYS = ["minRequest", "maxRequest", "minLimit", "maxLimit"] as const;

export type BoundKey = (typeof BOUND_KEYS)[number];

export const BOUND_LABELS: Record<BoundKey, string> = {
  minRequest: "Min request",
  maxRequest: "Max request",
  minLimit: "Min limit",
  maxLimit: "Max limit",
};

export interface BoundFormState {
  memory: string;
  cpu: string;
}

export interface LimitRangeFormState {
  tenantId: string;
  minRequest: BoundFormState;
  maxRequest: BoundFormState;
  minLimit: BoundFormState;
  maxLimit: BoundFormState;
}

const EMPTY_BOUND: BoundFormState = { memory: "", cpu: "" };

export const DEFAULT_LIMIT_RANGE_FORM: LimitRangeFormState = {
  tenantId: "",
  minRequest: EMPTY_BOUND,
  maxRequest: EMPTY_BOUND,
  minLimit: EMPTY_BOUND,
  maxLimit: EMPTY_BOUND,
};

/**
 * Renders one bound for the table. An absent bound is unbounded, which is a different statement
 * from a bound of zero -- so only `undefined` becomes the em dash, and a "0"/"0m" bound is printed
 * as the real bound it is.
 */
export function formatBound(bound: ResourceBound | undefined): string {
  return bound === undefined ? "—" : `${bound.memory} / ${bound.cpu}`;
}

/** Empty fields for every bound the spec leaves out, so blank round-trips as "no bound". */
export function limitRangeToForm(range: LimitRange): LimitRangeFormState {
  const form: LimitRangeFormState = { ...DEFAULT_LIMIT_RANGE_FORM, tenantId: range.tenantId };
  for (const key of BOUND_KEYS) {
    const bound = range[key];
    if (bound !== undefined) {
      form[key] = { memory: bound.memory, cpu: bound.cpu };
    }
  }
  return form;
}

function boundIsBlank(bound: BoundFormState): boolean {
  return bound.memory.trim() === "" && bound.cpu.trim() === "";
}

/** The first problem with the form, or null when it is ready to submit. */
export function validateLimitRangeForm(form: LimitRangeFormState): string | null {
  if (form.tenantId.trim() === "") {
    return "Tenant is required";
  }
  for (const key of BOUND_KEYS) {
    const bound = form[key];
    if (boundIsBlank(bound)) continue;
    if (bound.memory.trim() === "" || bound.cpu.trim() === "") {
      return `${BOUND_LABELS[key]} needs both memory and cpu — a bound is the pair, not one half of it`;
    }
  }
  if (BOUND_KEYS.every((key) => boundIsBlank(form[key]))) {
    return "Declare at least one bound — a LimitRange with none constrains nothing; delete it instead";
  }
  return null;
}

/**
 * Builds the spec to write. A bound left blank is omitted from the object entirely rather than
 * sent as zeroes: the API reads an absent key as "unbounded", so writing `{memory:"0",cpu:"0"}`
 * for a field the operator simply did not fill in would silently forbid every workload instead.
 */
export function buildLimitRange(form: LimitRangeFormState): LimitRange {
  const spec: LimitRange = { tenantId: form.tenantId.trim() };
  for (const key of BOUND_KEYS) {
    const bound = form[key];
    if (boundIsBlank(bound)) continue;
    spec[key] = { memory: bound.memory.trim(), cpu: bound.cpu.trim() };
  }
  return spec;
}

/* --------------------------------- screen ---------------------------------- */

function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="mb-3 rounded border border-status-bad/40 bg-status-bad-bg/40 px-3 py-2 text-xs text-status-bad">
      {message}
    </div>
  );
}

function Confirm({
  title,
  description,
  onConfirm,
}: {
  title: string;
  description: string;
  onConfirm: () => void;
}) {
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <button className="text-muted-foreground hover:text-status-bad" aria-label={title}>
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction
            onClick={onConfirm}
            className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
          >
            Delete
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/** Tenant dropdown, falling back to free text while the tenant list hasn't loaded yet -- the same
 * fallback the networking screen's own picker uses. */
function TenantPicker({
  value,
  tenantIds,
  disabled,
  onChange,
}: {
  value: string;
  tenantIds: string[];
  disabled?: boolean;
  onChange: (v: string) => void;
}) {
  if (tenantIds.length === 0) {
    return (
      <Input
        className="h-8 w-44 font-mono text-xs"
        placeholder="acme"
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
      />
    );
  }
  return (
    <Select value={value} onValueChange={onChange} disabled={disabled}>
      <SelectTrigger className="h-8 w-44 font-mono text-xs">
        <SelectValue placeholder="Pick tenant" />
      </SelectTrigger>
      <SelectContent>
        {tenantIds.map((id) => (
          <SelectItem key={id} value={id} className="font-mono text-xs">
            {id}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

function BoundFields({
  boundKey,
  value,
  onChange,
}: {
  boundKey: BoundKey;
  value: BoundFormState;
  onChange: (v: BoundFormState) => void;
}) {
  const blank = value.memory.trim() === "" && value.cpu.trim() === "";
  return (
    <div className="grid gap-1">
      <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
        {BOUND_LABELS[boundKey]}
      </Label>
      <div className="flex items-center gap-1">
        <Input
          className="h-8 w-24 font-mono text-xs"
          value={value.memory}
          aria-label={`${BOUND_LABELS[boundKey]} memory`}
          placeholder="unbounded"
          onChange={(e) => onChange({ ...value, memory: e.target.value })}
        />
        <Input
          className="h-8 w-20 font-mono text-xs"
          value={value.cpu}
          aria-label={`${BOUND_LABELS[boundKey]} cpu`}
          placeholder="unbounded"
          onChange={(e) => onChange({ ...value, cpu: e.target.value })}
        />
        <button
          type="button"
          disabled={blank}
          onClick={() => onChange({ memory: "", cpu: "" })}
          className="text-muted-foreground hover:text-foreground disabled:opacity-30"
          aria-label={`Clear ${BOUND_LABELS[boundKey].toLowerCase()}`}
        >
          <X className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  );
}

function LimitRangesPage() {
  const { items, loading, loaded, error, load, refresh, fetchOne, save, remove } =
    useLimitRangesStore();
  const tenants = useTenantsStore((s) => s.items);
  const loadTenants = useTenantsStore((s) => s.loadFirstPage);

  const [editing, setEditing] = useState<string | null>(null);
  const [form, setForm] = useState<LimitRangeFormState>(DEFAULT_LIMIT_RANGE_FORM);

  useEffect(() => {
    if (!loaded) load();
    if (tenants.length === 0) loadTenants();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function reset() {
    setEditing(null);
    setForm(DEFAULT_LIMIT_RANGE_FORM);
  }

  // Re-reads the single resource rather than editing the row the list happens to hold, so a
  // concurrent write by someone else is not silently overwritten with stale bounds.
  async function edit(tenantId: string) {
    try {
      const range = await fetchOne(tenantId);
      setEditing(tenantId);
      setForm(limitRangeToForm(range));
    } catch (err) {
      notifyApiError(err);
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const problem = validateLimitRangeForm(form);
    if (problem) {
      toast.error(problem);
      return;
    }
    const spec = buildLimitRange(form);
    try {
      await save(spec);
      toast.success(`Limit range for ${spec.tenantId} saved`);
      reset();
    } catch (err) {
      notifyApiError(err);
    }
  }

  return (
    <PageContainer>
      <PageHeader
        title="LimitRanges"
        subtitle={DESCRIPTION}
        actions={
          <Button variant="outline" size="sm" onClick={() => refresh()} disabled={loading}>
            <RefreshCw className="h-3.5 w-3.5" />
            Refresh
          </Button>
        }
      />

      {error && <ErrorBanner message={error} />}

      <form onSubmit={submit} className="mb-4 grid gap-3 rounded border border-border bg-card p-3">
        <div className="flex flex-wrap items-end gap-3">
          <div className="grid gap-1">
            <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
              Tenant
            </Label>
            <TenantPicker
              value={form.tenantId}
              tenantIds={tenants.map((t) => t.id)}
              disabled={editing !== null}
              onChange={(v) => setForm({ ...form, tenantId: v })}
            />
          </div>
          {BOUND_KEYS.map((key) => (
            <BoundFields
              key={key}
              boundKey={key}
              value={form[key]}
              onChange={(v) => setForm({ ...form, [key]: v })}
            />
          ))}
          <div className="flex items-center gap-2 pb-0.5">
            {editing && (
              <Button type="button" variant="ghost" size="sm" onClick={reset}>
                <X className="h-3.5 w-3.5" />
                Cancel
              </Button>
            )}
            <Button type="submit" size="sm" disabled={loading}>
              <Plus className="h-3.5 w-3.5" />
              {editing ? "Save limit range" : "Create limit range"}
            </Button>
          </div>
        </div>
        <p className="text-[11px] text-muted-foreground">
          Each bound is a memory + cpu pair (e.g. <span className="font-mono">512Mi</span> and{" "}
          <span className="font-mono">500m</span>). Leave both fields of a bound empty to leave that
          side unbounded — an empty bound is not a bound of zero.
        </p>
      </form>

      <div className="mb-2 text-xs text-muted-foreground">{items.length} limit ranges</div>

      <div className="overflow-x-auto rounded border border-border bg-card">
        <table className="w-full text-xs">
          <thead className="bg-muted/50 text-muted-foreground">
            <tr className="text-left">
              <th className="px-2 py-1.5 font-medium">Tenant</th>
              {BOUND_KEYS.map((key) => (
                <th key={key} className="px-2 py-1.5 font-medium">
                  {BOUND_LABELS[key]}
                </th>
              ))}
              <th className="px-2 py-1.5 font-medium w-20"></th>
            </tr>
          </thead>
          <tbody>
            {items.map((r) => (
              <tr key={r.tenantId} className="border-t border-border hover:bg-muted/30">
                <td className="px-2 py-1.5 font-mono">{r.tenantId}</td>
                {BOUND_KEYS.map((key) => (
                  <td key={key} className="px-2 py-1.5 font-mono">
                    {formatBound(r[key])}
                  </td>
                ))}
                <td className="px-2 py-1.5">
                  <div className="flex items-center gap-3">
                    <button
                      onClick={() => edit(r.tenantId)}
                      className="text-muted-foreground hover:text-foreground"
                      aria-label={`Edit limit range for ${r.tenantId}`}
                    >
                      <Pencil className="h-3.5 w-3.5" />
                    </button>
                    <Confirm
                      title={`Delete limit range for ${r.tenantId}?`}
                      description="Every workload in this tenant stops being bounded per-workload; only the tenant's aggregate quota still applies."
                      onConfirm={async () => {
                        try {
                          await remove(r.tenantId);
                          toast.success("Limit range deleted");
                          if (editing === r.tenantId) reset();
                        } catch (err) {
                          notifyApiError(err);
                        }
                      }}
                    />
                  </div>
                </td>
              </tr>
            ))}
            {loading && items.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-6 text-center text-muted-foreground">
                  Loading…
                </td>
              </tr>
            )}
            {!loading && items.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-6 text-center text-muted-foreground">
                  No limit ranges. Every tenant&apos;s workloads are bounded only by its own
                  aggregate quota.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </PageContainer>
  );
}
