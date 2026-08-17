// Pan/zoom viewer for large architecture diagrams (SVG or raster). Self-contained -- no new
// npm dependency -- so the docs build (`bun run build`) never needs one added for this.
//
// Wheel: zoom toward the cursor. Drag: pan once zoomed in. Buttons: zoom in/out/reset, plus a
// fullscreen toggle for diagrams too dense to read at the doc column width. Keyboard: +/-/0
// while the viewer has focus, Escape leaves fullscreen.
import React from 'react';
import useBaseUrl from '@docusaurus/useBaseUrl';
import styles from './styles.module.css';

const MIN_SCALE = 1;
const MAX_SCALE = 8;
const WHEEL_ZOOM_SPEED = 0.0018;
const STEP_ZOOM_FACTOR = 1.5;

type Props = {
  /** Path under static/, e.g. "/diagrams/control-plane-reconcile.svg". */
  src: string;
  /** States the takeaway, not "diagram of X" -- shown as visible caption too. */
  alt: string;
  /** Optional short caption under the frame; defaults to `alt` when omitted. */
  caption?: string;
  /** Caps the diagram's width at the 1x (un-zoomed) scale. */
  width?: number | string;
};

type Point = {x: number; y: number};

export default function ZoomableDiagram({src, alt, caption, width = 720}: Props): React.ReactNode {
  const resolvedSrc = useBaseUrl(src);
  const frameRef = React.useRef<HTMLDivElement | null>(null);
  const dragState = React.useRef<{pointerId: number; start: Point; origin: Point} | null>(null);

  const [scale, setScale] = React.useState(1);
  const [offset, setOffset] = React.useState<Point>({x: 0, y: 0});
  const [fullscreen, setFullscreen] = React.useState(false);
  const [dragging, setDragging] = React.useState(false);

  const clampScale = (next: number) => Math.min(MAX_SCALE, Math.max(MIN_SCALE, next));

  // Zoom while keeping the point under the cursor/anchor visually fixed, so zooming feels
  // anchored to what the reader is looking at rather than recentering the whole diagram.
  const zoomAt = React.useCallback(
    (anchor: Point, nextScaleRaw: number) => {
      const frame = frameRef.current;
      if (!frame) return;
      const nextScale = clampScale(nextScaleRaw);
      setScale((prevScale) => {
        const rect = frame.getBoundingClientRect();
        const cx = anchor.x - rect.left - rect.width / 2;
        const cy = anchor.y - rect.top - rect.height / 2;
        setOffset((prevOffset) => {
          const ratio = nextScale / prevScale;
          return {
            x: cx - (cx - prevOffset.x) * ratio,
            y: cy - (cy - prevOffset.y) * ratio,
          };
        });
        return nextScale;
      });
    },
    [],
  );

  const resetView = React.useCallback(() => {
    setScale(1);
    setOffset({x: 0, y: 0});
  }, []);

  const stepZoom = (direction: 1 | -1) => {
    const frame = frameRef.current;
    if (!frame) return;
    const rect = frame.getBoundingClientRect();
    const center = {x: rect.left + rect.width / 2, y: rect.top + rect.height / 2};
    const factor = direction === 1 ? STEP_ZOOM_FACTOR : 1 / STEP_ZOOM_FACTOR;
    zoomAt(center, scale * factor);
  };

  const onWheel = (event: React.WheelEvent<HTMLDivElement>) => {
    if (scale === 1 && event.deltaY > 0) return; // already at rest, nothing to zoom out further
    event.preventDefault();
    const factor = Math.exp(-event.deltaY * WHEEL_ZOOM_SPEED);
    zoomAt({x: event.clientX, y: event.clientY}, scale * factor);
  };

  const onPointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
    if (scale <= 1) return; // nothing to pan until zoomed in
    (event.target as Element).setPointerCapture(event.pointerId);
    dragState.current = {
      pointerId: event.pointerId,
      start: {x: event.clientX, y: event.clientY},
      origin: offset,
    };
    setDragging(true);
  };

  const onPointerMove = (event: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragState.current;
    if (!drag || drag.pointerId !== event.pointerId) return;
    setOffset({
      x: drag.origin.x + (event.clientX - drag.start.x),
      y: drag.origin.y + (event.clientY - drag.start.y),
    });
  };

  const endDrag = (event: React.PointerEvent<HTMLDivElement>) => {
    if (dragState.current?.pointerId === event.pointerId) {
      dragState.current = null;
      setDragging(false);
    }
  };

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key === '+' || event.key === '=') stepZoom(1);
    else if (event.key === '-') stepZoom(-1);
    else if (event.key === '0') resetView();
    else if (event.key === 'Escape' && fullscreen) setFullscreen(false);
  };

  // Leaving fullscreen (however triggered) should also hand the reader back a resting view.
  React.useEffect(() => {
    if (!fullscreen) resetView();
  }, [fullscreen, resetView]);

  const shownCaption = caption ?? alt;

  return (
    <figure className={styles.figure} style={fullscreen ? undefined : {maxWidth: width}}>
      <div
        className={fullscreen ? styles.overlay : undefined}
        onClick={fullscreen ? (event) => event.target === event.currentTarget && setFullscreen(false) : undefined}
      >
        <div
          ref={frameRef}
          className={`${styles.frame} ${fullscreen ? styles.frameFullscreen : ''} ${
            dragging ? styles.dragging : ''
          } ${scale > 1 ? styles.zoomed : ''}`}
          tabIndex={0}
          role="img"
          aria-label={alt}
          onWheel={onWheel}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
          onKeyDown={onKeyDown}
        >
          <img
            src={resolvedSrc}
            alt={alt}
            draggable={false}
            className={styles.image}
            style={{
              transform: `translate(${offset.x}px, ${offset.y}px) scale(${scale})`,
            }}
          />
          <div className={styles.controls}>
            <button
              type="button"
              className={styles.controlButton}
              onClick={() => stepZoom(-1)}
              disabled={scale <= MIN_SCALE}
              aria-label="Zoom out"
              title="Zoom out"
            >
              −
            </button>
            <button
              type="button"
              className={styles.controlButton}
              onClick={resetView}
              disabled={scale === 1 && offset.x === 0 && offset.y === 0}
              aria-label="Reset zoom"
              title="Reset zoom"
            >
              {Math.round(scale * 100)}%
            </button>
            <button
              type="button"
              className={styles.controlButton}
              onClick={() => stepZoom(1)}
              disabled={scale >= MAX_SCALE}
              aria-label="Zoom in"
              title="Zoom in"
            >
              +
            </button>
            <button
              type="button"
              className={styles.controlButton}
              onClick={() => setFullscreen((value) => !value)}
              aria-label={fullscreen ? 'Exit fullscreen' : 'View fullscreen'}
              title={fullscreen ? 'Exit fullscreen' : 'View fullscreen'}
            >
              {fullscreen ? '⤢' : '⛶'}
            </button>
          </div>
        </div>
      </div>
      <figcaption className={styles.caption}>{shownCaption}</figcaption>
    </figure>
  );
}
