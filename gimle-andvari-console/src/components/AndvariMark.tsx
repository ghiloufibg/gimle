import { cn } from "@/lib/utils";

/**
 * Andvari's emblem: a rune-etched hexagonal vault door -- a registry that stores and guards
 * artifacts, the same "guardian" visual language as Fafnir's own dragon-and-gem mark, in this
 * family's shared primary/signal palette. Inline SVG rather than a bundled raster asset: crisp at
 * both the ~32px sidebar size and the ~80px login size with zero extra network request, and no
 * binary asset to keep in sync across a design iteration.
 */
export function AndvariMark({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 64 64"
      role="img"
      aria-label="Gimlé Andvari mark"
      className={cn("shrink-0", className)}
    >
      <polygon
        points="32,3 58,17.5 58,46.5 32,61 6,46.5 6,17.5"
        fill="color-mix(in oklab, var(--color-primary) 14%, transparent)"
        stroke="var(--color-primary)"
        strokeWidth="2.5"
        strokeLinejoin="round"
      />
      <rect
        x="21"
        y="19"
        width="22"
        height="26"
        rx="1.5"
        fill="color-mix(in oklab, var(--color-primary) 30%, transparent)"
        stroke="var(--color-primary)"
        strokeWidth="2"
      />
      <line x1="32" y1="19" x2="32" y2="45" stroke="var(--color-primary)" strokeWidth="2" />
      <circle cx="32" cy="32" r="3" fill="var(--color-signal)" />
      <circle cx="25" cy="24" r="1.4" fill="var(--color-primary)" />
      <circle cx="39" cy="24" r="1.4" fill="var(--color-primary)" />
      <circle cx="25" cy="40" r="1.4" fill="var(--color-primary)" />
      <circle cx="39" cy="40" r="1.4" fill="var(--color-primary)" />
    </svg>
  );
}
