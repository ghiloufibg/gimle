/**
 * Small bridge so the palette and the toolbar can drop a node in the middle of
 * the visible canvas, or refit it, without threading refs through the tree.
 */
export const canvasBridge: {
  center: () => { x: number; y: number };
  fit: () => void;
} = {
  center: () => ({ x: 120, y: 120 }),
  fit: () => {},
};
