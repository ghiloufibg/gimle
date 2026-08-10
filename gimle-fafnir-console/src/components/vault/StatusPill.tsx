export function StatusPill({ label = "Operational" }: { label?: string }) {
  return (
    <span className="inline-flex items-center gap-2 border border-status-ok/40 bg-status-ok-bg px-2 py-1">
      <span className="relative flex size-1.5">
        <span className="absolute inline-flex size-full animate-ping rounded-full bg-status-ok opacity-75" />
        <span className="relative inline-flex size-1.5 rounded-full bg-status-ok" />
      </span>
      <span className="hud-label text-status-ok">{label}</span>
    </span>
  );
}
