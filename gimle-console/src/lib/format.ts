export function fmtBytes(b: number): string {
  if (b < 1024) return `${b} B`;
  const units = ["KiB", "MiB", "GiB", "TiB"];
  let v = b / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(v >= 10 ? 0 : 1)} ${units[i]}`;
}

export function fmtMillicores(m: number): string {
  if (m >= 1000) return `${(m / 1000).toFixed(2)} cores`;
  return `${m} mCPU`;
}

export function fmtRelativeTime(iso: string | null): string {
  if (!iso) return "never";
  const ms = Date.now() - new Date(iso).getTime();
  if (ms < 0) return "now";
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

export function isStale(iso: string | null, thresholdSec = 30): boolean {
  if (!iso) return true;
  return Date.now() - new Date(iso).getTime() > thresholdSec * 1000;
}
