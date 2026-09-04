export function parseMemory(value: string | undefined): number {
  if (!value) return 0;
  const m = /^(\d+(?:\.\d+)?)\s*(Ki|Mi|Gi|Ti)?$/.exec(value.trim());
  if (!m) return NaN;
  const n = Number(m[1]);
  const unit = m[2] ?? "";
  const mult: Record<string, number> = {
    "": 1,
    Ki: 1024,
    Mi: 1024 ** 2,
    Gi: 1024 ** 3,
    Ti: 1024 ** 4,
  };
  return n * mult[unit];
}

export function formatMemory(bytes: number): string {
  if (bytes >= 1024 ** 3 && bytes % 1024 ** 3 === 0) return `${bytes / 1024 ** 3}Gi`;
  if (bytes >= 1024 ** 2) return `${Math.round(bytes / 1024 ** 2)}Mi`;
  return `${bytes}`;
}

export function parseCpu(value: string | undefined): number {
  if (!value) return 0;
  const s = value.trim();
  const milli = /^(\d+(?:\.\d+)?)m$/.exec(s);
  if (milli) return Number(milli[1]);
  const cores = /^(\d+(?:\.\d+)?)$/.exec(s);
  if (cores) return Number(cores[1]) * 1000;
  return NaN;
}

export function formatCpu(millicores: number): string {
  return `${millicores}m`;
}

export function isValidMemory(value: string | undefined): boolean {
  const v = parseMemory(value);
  return Number.isFinite(v) && v > 0;
}

export function isValidCpu(value: string | undefined): boolean {
  const v = parseCpu(value);
  return Number.isFinite(v) && v > 0;
}
