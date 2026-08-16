interface Props {
  size?: number;
  className?: string;
}

/**
 * Gjallarhorn emblem — minimal geometric war-horn, strokes in currentColor.
 * Readable at 16px and 28px.
 */
export function SagaEmblem({ size = 28, className }: Props) {
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
      <path d="M4 8h6l16 6-16 6H4z" />
      <path d="M10 14v4" />
      <path d="M26 11.5v11" strokeWidth={2.6} />
    </svg>
  );
}

export const SAGA_FAVICON = `data:image/svg+xml,${encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" fill="none" stroke="#1a8f78" stroke-width="2.6" stroke-linecap="square"><path d="M4 8h6l16 6-16 6H4z"/><path d="M10 14v4"/><path d="M26 11.5v11"/></svg>`,
)}`;
