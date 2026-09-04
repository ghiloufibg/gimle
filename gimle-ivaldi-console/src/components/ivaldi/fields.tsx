import { X } from "lucide-react";
import { useState, type ReactNode } from "react";

import type { Problem } from "@/lib/blueprint";
import {
  formatCpu,
  formatMemory,
  isValidCpu,
  isValidMemory,
  parseCpu,
  parseMemory,
} from "@/lib/units";
import { cn } from "@/lib/utils";

export function ProblemList({ problems }: { problems: Problem[] }) {
  if (!problems.length) return null;
  return (
    <ul className="mt-0.5 space-y-0.5">
      {problems.map((p, i) => (
        <li
          key={`${p.code}-${i}`}
          className={cn(
            "font-mono text-[10px] leading-tight",
            p.severity === "error" && "text-status-bad",
            p.severity === "warning" && "text-status-warn",
            p.severity === "info" && "text-status-info",
          )}
        >
          {p.code}: {p.message}
        </li>
      ))}
    </ul>
  );
}

export function Field({
  label,
  problems = [],
  children,
  hint,
}: {
  label: string;
  problems?: Problem[];
  children: ReactNode;
  hint?: string;
}) {
  return (
    <div className="space-y-1">
      <div className="hud-label">{label}</div>
      {children}
      {hint && <div className="text-[10px] text-muted-foreground">{hint}</div>}
      <ProblemList problems={problems} />
    </div>
  );
}

const inputClass =
  "h-7 w-full rounded-sm border border-border bg-background px-2 font-mono text-[11px] text-foreground outline-none focus:border-primary";

export function TextField({
  label,
  value,
  onChange,
  problems,
  hint,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  problems?: Problem[];
  hint?: string;
  placeholder?: string;
}) {
  return (
    <Field label={label} problems={problems} hint={hint}>
      <input
        className={inputClass}
        value={value}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
      />
    </Field>
  );
}

export function NumberField({
  label,
  value,
  onChange,
  problems,
  hint,
}: {
  label: string;
  value: number | undefined;
  onChange: (value: number) => void;
  problems?: Problem[];
  hint?: string;
}) {
  return (
    <Field label={label} problems={problems} hint={hint}>
      <input
        type="number"
        className={cn(inputClass, "num")}
        value={value ?? ""}
        onChange={(e) => onChange(Number(e.target.value))}
      />
    </Field>
  );
}

export function SelectField<T extends string>({
  label,
  value,
  options,
  onChange,
  problems,
}: {
  label: string;
  value: T;
  options: readonly T[];
  onChange: (value: T) => void;
  problems?: Problem[];
}) {
  return (
    <Field label={label} problems={problems}>
      <select className={inputClass} value={value} onChange={(e) => onChange(e.target.value as T)}>
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    </Field>
  );
}

export function CheckboxField({
  label,
  checked,
  onChange,
}: {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label className="flex items-center gap-2 py-0.5">
      <input
        type="checkbox"
        className="size-3 accent-[var(--color-primary)]"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
      />
      <span className="hud-label">{label}</span>
    </label>
  );
}

export function ListField({
  label,
  values,
  onChange,
  placeholder,
  problems,
}: {
  label: string;
  values: string[];
  onChange: (values: string[]) => void;
  placeholder?: string;
  problems?: Problem[];
}) {
  const [draft, setDraft] = useState("");
  return (
    <Field label={label} problems={problems}>
      <div className="flex flex-wrap gap-1">
        {values.map((v) => (
          <span
            key={v}
            className="inline-flex items-center gap-1 rounded-sm border border-border bg-secondary px-1.5 py-0.5 font-mono text-[10px] text-secondary-foreground"
          >
            {v}
            <button
              type="button"
              onClick={() => onChange(values.filter((x) => x !== v))}
              className="text-muted-foreground hover:text-status-bad"
            >
              <X className="size-2.5" />
            </button>
          </span>
        ))}
      </div>
      <input
        className={inputClass}
        value={draft}
        placeholder={placeholder ?? "Add and press Enter"}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && draft.trim()) {
            e.preventDefault();
            if (!values.includes(draft.trim())) onChange([...values, draft.trim()]);
            setDraft("");
          }
        }}
      />
    </Field>
  );
}

const UNIT_ERROR = (what: string): Problem => ({
  code: "UNIT_INVALID",
  severity: "error",
  message: `Not a valid ${what} value`,
});

export function MemoryField({
  label,
  value,
  onChange,
  problems = [],
  hint,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  problems?: Problem[];
  hint?: string;
}) {
  const ok = isValidMemory(value);
  const bytes = parseMemory(value);
  return (
    <Field
      label={label}
      problems={ok ? problems : [UNIT_ERROR("memory"), ...problems]}
      hint={
        hint ?? (ok ? `${formatMemory(bytes)} · ${bytes.toLocaleString()} B` : "Ki / Mi / Gi / Ti")
      }
    >
      <input
        className={cn(inputClass, "num", !ok && "border-status-bad")}
        value={value}
        placeholder="256Mi"
        onChange={(e) => onChange(e.target.value)}
      />
    </Field>
  );
}

export function CpuField({
  label,
  value,
  onChange,
  problems = [],
  hint,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  problems?: Problem[];
  hint?: string;
}) {
  const ok = isValidCpu(value);
  const milli = parseCpu(value);
  return (
    <Field
      label={label}
      problems={ok ? problems : [UNIT_ERROR("cpu"), ...problems]}
      hint={
        hint ?? (ok ? `${formatCpu(milli)} · ${(milli / 1000).toFixed(3)} cores` : "500m or 0.5")
      }
    >
      <input
        className={cn(inputClass, "num", !ok && "border-status-bad")}
        value={value}
        placeholder="500m"
        onChange={(e) => onChange(e.target.value)}
      />
    </Field>
  );
}

export function MemoryBytesField({
  label,
  bytes,
  onChange,
  problems,
  hint,
}: {
  label: string;
  bytes: number;
  onChange: (bytes: number) => void;
  problems?: Problem[];
  hint?: string;
}) {
  const [text, setText] = useState(formatMemory(bytes));
  const [editing, setEditing] = useState(false);
  const shown = editing ? text : formatMemory(bytes);
  const ok = isValidMemory(shown);
  return (
    <Field
      label={label}
      problems={ok ? problems : [UNIT_ERROR("memory"), ...(problems ?? [])]}
      hint={hint ?? (ok ? `${parseMemory(shown).toLocaleString()} B` : "Ki / Mi / Gi / Ti")}
    >
      <input
        className={cn(inputClass, "num", !ok && "border-status-bad")}
        value={shown}
        placeholder="1Gi"
        onFocus={() => {
          setText(formatMemory(bytes));
          setEditing(true);
        }}
        onBlur={() => setEditing(false)}
        onChange={(e) => {
          setText(e.target.value);
          const parsed = parseMemory(e.target.value);
          if (Number.isFinite(parsed) && parsed > 0) onChange(parsed);
        }}
      />
    </Field>
  );
}

export function MillicoresField({
  label,
  value,
  onChange,
  problems,
}: {
  label: string;
  value: number;
  onChange: (value: number) => void;
  problems?: Problem[];
}) {
  const [text, setText] = useState(formatCpu(value));
  const [editing, setEditing] = useState(false);
  const shown = editing ? text : formatCpu(value);
  const ok = isValidCpu(shown);
  return (
    <Field
      label={label}
      problems={ok ? problems : [UNIT_ERROR("cpu"), ...(problems ?? [])]}
      hint={ok ? `${(parseCpu(shown) / 1000).toFixed(3)} cores` : "4000m or 4"}
    >
      <input
        className={cn(inputClass, "num", !ok && "border-status-bad")}
        value={shown}
        placeholder="4000m"
        onFocus={() => {
          setText(formatCpu(value));
          setEditing(true);
        }}
        onBlur={() => setEditing(false)}
        onChange={(e) => {
          setText(e.target.value);
          const parsed = parseCpu(e.target.value);
          if (Number.isFinite(parsed) && parsed > 0) onChange(parsed);
        }}
      />
    </Field>
  );
}
