import type { Scene } from "./types";

export interface Viewport {
  x: number;
  y: number;
  scale: number;
}
export function zoomAt(
  view: Viewport,
  pointer: { x: number; y: number },
  factor: number,
): Viewport {
  const scale = Math.min(3, Math.max(0.05, view.scale * factor));
  const ratio = scale / view.scale;
  return {
    x: pointer.x - (pointer.x - view.x) * ratio,
    y: pointer.y - (pointer.y - view.y) * ratio,
    scale,
  };
}
export function fitViewport(
  size: Scene["size"],
  width: number,
  height: number,
): Viewport {
  if (width <= 64 || height <= 64) return { x: 0, y: 0, scale: 1 };
  const left = size["min-x"] ?? 0,
    top = size["min-y"] ?? 0;
  const w = Math.max(1, size.w - left),
    h = Math.max(1, size.h - top);
  const scale = Math.min(
    1.5,
    Math.max(0.05, Math.min((width - 64) / w, (height - 64) / h)),
  );
  return {
    x: (width - w * scale) / 2 - left * scale,
    y: (height - h * scale) / 2 - top * scale,
    scale,
  };
}
