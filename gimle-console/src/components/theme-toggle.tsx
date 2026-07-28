import { useEffect } from "react";
import { useThemeStore } from "@/stores/useThemeStore";
import { Button } from "@/components/ui/button";
import { Moon, Sun } from "lucide-react";

export function ThemeToggle() {
  const theme = useThemeStore((s) => s.theme);
  const toggle = useThemeStore((s) => s.toggle);
  const init = useThemeStore((s) => s.init);
  useEffect(() => {
    init();
  }, [init]);
  return (
    <Button variant="ghost" size="sm" onClick={toggle} aria-label="Toggle theme" className="gap-2">
      {theme === "dark" ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
      <span className="text-xs">{theme === "dark" ? "Light" : "Dark"}</span>
    </Button>
  );
}
