import fafnirMark from "@/assets/fafnir-mark.png";
import { cn } from "@/lib/utils";

export function FafnirMark({ className }: { className?: string }) {
  return (
    <img
      src={fafnirMark}
      alt="Gimlé Fafnir Vault emblem"
      width={1024}
      height={1024}
      className={cn("shrink-0 object-contain", className)}
    />
  );
}
