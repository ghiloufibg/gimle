interface IvaldiEmblemProps {
  size?: number;
  className?: string;
}

export function IvaldiEmblem({ size = 24, className }: IvaldiEmblemProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      stroke="currentColor"
      strokeWidth={2.2}
      strokeLinecap="square"
      strokeLinejoin="miter"
      aria-hidden="true"
      className={className}
    >
      <path d="M7 15h19l-4 5H12z" />
      <path d="M7 15L3 16.5 7 18" />
      <path d="M13 20v4h8v-4M9 24h16v3H9z" />
      <circle cx="10" cy="8" r="1.6" />
      <circle cx="16" cy="5" r="1.6" />
      <circle cx="22" cy="8" r="1.6" />
      <path d="M11.5 7.3l3.1-1.6M17.4 5.7l3.1 1.6M16 6.8V15" />
    </svg>
  );
}

const FAVICON_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" fill="none" stroke="#1a8f78" stroke-width="2.2" stroke-linecap="square" stroke-linejoin="miter"><path d="M7 15h19l-4 5H12z"/><path d="M7 15L3 16.5 7 18"/><path d="M13 20v4h8v-4M9 24h16v3H9z"/><circle cx="10" cy="8" r="1.6"/><circle cx="16" cy="5" r="1.6"/><circle cx="22" cy="8" r="1.6"/><path d="M11.5 7.3l3.1-1.6M17.4 5.7l3.1 1.6M16 6.8V15"/></svg>`;

export const IVALDI_FAVICON = `data:image/svg+xml,${encodeURIComponent(FAVICON_SVG)}`;

export function IvaldiWordmark({ compact = false }: { compact?: boolean }) {
  return (
    <div className="flex items-center gap-2.5">
      <IvaldiEmblem size={compact ? 20 : 28} className="text-primary" />
      <div className="leading-tight">
        <div
          className="font-mono font-bold text-foreground"
          style={{ letterSpacing: "0.18em", fontSize: compact ? 13 : 15 }}
        >
          IVALDI
        </div>
        {!compact && <div className="hud-label">gimle // cluster designer</div>}
      </div>
    </div>
  );
}
