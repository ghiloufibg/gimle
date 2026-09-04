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
      <path d="M4 21 L28 21 L28 24 L22 24 L20 27 L12 27 L10 24 L4 24 Z" />
      <path d="M4 21 L2 18 L8 18" />
      <path d="M7 7 L16 4 L25 7" />
      <path d="M16 4 L16 18" />
      <path d="M6.6 7 A1.4 1.4 0 1 0 7.4 7" />
      <path d="M24.6 7 A1.4 1.4 0 1 0 25.4 7" />
    </svg>
  );
}

const FAVICON_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" fill="none" stroke="#1a8f78" stroke-width="2.2" stroke-linecap="square" stroke-linejoin="miter"><path d="M4 21 L28 21 L28 24 L22 24 L20 27 L12 27 L10 24 L4 24 Z"/><path d="M4 21 L2 18 L8 18"/><path d="M7 7 L16 4 L25 7"/><path d="M16 4 L16 18"/><path d="M6.6 7 A1.4 1.4 0 1 0 7.4 7"/><path d="M24.6 7 A1.4 1.4 0 1 0 25.4 7"/></svg>`;

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
