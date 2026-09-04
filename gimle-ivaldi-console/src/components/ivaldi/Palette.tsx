import { PanelLeftClose, PanelLeftOpen, Search } from "lucide-react";

import { APP_KINDS, KIND_LABELS, PLATFORM_KINDS, type NodeKind } from "@/lib/blueprint";
import { useUiStore } from "@/stores/useUiStore";

import { KIND_META } from "./kinds";

function PaletteItem({ kind }: { kind: NodeKind }) {
  const meta = KIND_META[kind];
  const Icon = meta.icon;
  return (
    <div
      draggable
      onDragStart={(event) => {
        event.dataTransfer.setData("application/ivaldi-kind", kind);
        event.dataTransfer.effectAllowed = "move";
      }}
      className="group flex cursor-grab items-start gap-2 rounded-sm border border-border bg-card px-2 py-1.5 hover:border-primary/60 active:cursor-grabbing"
    >
      <Icon className="mt-0.5 size-3.5 shrink-0 text-primary" />
      <div className="min-w-0">
        <div className="font-mono text-[11px] font-semibold text-foreground">
          {KIND_LABELS[kind]}
        </div>
        <div className="truncate text-[10px] text-muted-foreground">{meta.hint}</div>
      </div>
    </div>
  );
}

export function Palette() {
  const query = useUiStore((s) => s.paletteQuery);
  const setQuery = useUiStore((s) => s.setPaletteQuery);
  const collapsed = useUiStore((s) => s.paletteCollapsed);
  const toggle = useUiStore((s) => s.togglePalette);

  if (collapsed)
    return (
      <aside className="flex h-full w-8 shrink-0 flex-col items-center border-r border-border bg-sidebar py-2">
        <button
          onClick={toggle}
          title="Show palette"
          className="rounded-sm border border-border p-1 text-muted-foreground hover:border-primary hover:text-foreground"
        >
          <PanelLeftOpen className="size-3.5" />
        </button>
      </aside>
    );

  const match = (kind: NodeKind) =>
    !query.trim() ||
    KIND_LABELS[kind].toLowerCase().includes(query.toLowerCase()) ||
    KIND_META[kind].hint.toLowerCase().includes(query.toLowerCase());

  const platform = PLATFORM_KINDS.filter(match);
  const app = APP_KINDS.filter(match);

  return (
    <aside className="flex h-full w-[240px] shrink-0 flex-col border-r border-border bg-sidebar">
      <div className="flex items-center gap-1 border-b border-sidebar-border p-2">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-2 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search palette"
            className="h-7 w-full rounded-sm border border-border bg-background pl-7 pr-2 font-mono text-[11px] text-foreground outline-none focus:border-primary"
          />
        </div>
        <button
          onClick={toggle}
          title="Hide palette"
          className="shrink-0 rounded-sm border border-border p-1 text-muted-foreground hover:border-primary hover:text-foreground"
        >
          <PanelLeftClose className="size-3.5" />
        </button>
      </div>
      <div className="flex-1 space-y-4 overflow-y-auto p-2">
        <section className="space-y-1.5">
          <div className="hud-label px-0.5">Platform</div>
          {platform.map((k) => (
            <PaletteItem key={k} kind={k} />
          ))}
          {platform.length === 0 && (
            <div className="px-0.5 text-[10px] text-muted-foreground">No match</div>
          )}
        </section>
        <section className="space-y-1.5">
          <div className="hud-label px-0.5">Application</div>
          {app.map((k) => (
            <PaletteItem key={k} kind={k} />
          ))}
          {app.length === 0 && (
            <div className="px-0.5 text-[10px] text-muted-foreground">No match</div>
          )}
        </section>
      </div>
      <div className="border-t border-sidebar-border p-2 text-[10px] text-muted-foreground">
        Drag an item onto the canvas.
      </div>
    </aside>
  );
}
