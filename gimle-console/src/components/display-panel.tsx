import { useEffect } from "react";
import { LayoutGrid, Moon, Rows3, SlidersHorizontal, Sun } from "lucide-react";

import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useDisplayStore } from "@/stores/useDisplayStore";
import { useThemeStore } from "@/stores/useThemeStore";

function Segmented<T extends string>({
  value,
  options,
  onChange,
}: {
  value: T;
  options: { value: T; label: string; icon?: React.ReactNode; hint?: string }[];
  onChange: (v: T) => void;
}) {
  return (
    <div className="grid grid-cols-2 gap-1 rounded-sm border border-primary/20 bg-primary/5 p-1">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          onClick={() => onChange(o.value)}
          aria-pressed={value === o.value}
          className={cn(
            "flex flex-col items-start gap-0.5 rounded-sm px-2 py-1.5 text-left transition-colors",
            value === o.value
              ? "bg-primary/20 text-primary"
              : "text-muted-foreground hover:bg-primary/10 hover:text-foreground",
          )}
        >
          <span className="flex items-center gap-1.5 text-[11px] font-mono font-bold uppercase tracking-widest">
            {o.icon}
            {o.label}
          </span>
          {o.hint && (
            <span className="text-[10px] normal-case tracking-normal opacity-70">{o.hint}</span>
          )}
        </button>
      ))}
    </div>
  );
}

export function DisplayPanel() {
  const theme = useThemeStore((s) => s.theme);
  const setTheme = useThemeStore((s) => s.setTheme);
  const initTheme = useThemeStore((s) => s.init);

  const mode = useDisplayStore((s) => s.mode);
  const density = useDisplayStore((s) => s.density);
  const setMode = useDisplayStore((s) => s.setMode);
  const setDensity = useDisplayStore((s) => s.setDensity);
  const initDisplay = useDisplayStore((s) => s.init);

  useEffect(() => {
    initTheme();
    initDisplay();
  }, [initTheme, initDisplay]);

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          variant="ghost"
          size="sm"
          className="h-7 gap-1.5 px-2 text-[10px] font-mono uppercase tracking-widest"
          aria-label="Display settings"
        >
          <SlidersHorizontal className="h-3.5 w-3.5" />
          Display
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-72 space-y-4 p-3">
        <div>
          <p className="hud-label mb-1.5">Layout</p>
          <Segmented
            value={mode}
            onChange={setMode}
            options={[
              {
                value: "hud",
                label: "HUD",
                icon: <Rows3 className="h-3 w-3" />,
                hint: "Tactical grid (default)",
              },
              {
                value: "signal",
                label: "Signal",
                icon: <LayoutGrid className="h-3 w-3" />,
                hint: "Card decks",
              },
            ]}
          />
        </div>

        <div>
          <p className="hud-label mb-1.5">Density</p>
          <Segmented
            value={density}
            onChange={setDensity}
            options={[
              { value: "compact", label: "Compact", hint: "Max data per screen" },
              { value: "roomy", label: "Roomy", hint: "More breathing room" },
            ]}
          />
        </div>

        <div>
          <p className="hud-label mb-1.5">Theme</p>
          <Segmented
            value={theme}
            onChange={setTheme}
            options={[
              {
                value: "dark",
                label: "Dark",
                icon: <Moon className="h-3 w-3" />,
                hint: "Instrument panel",
              },
              {
                value: "light",
                label: "Light",
                icon: <Sun className="h-3 w-3" />,
                hint: "Cool paper",
              },
            ]}
          />
        </div>
      </PopoverContent>
    </Popover>
  );
}
