import { describe, expect, it } from "vitest";
import { fitViewport, zoomAt } from "./viewport";

describe("SVG viewport", () => {
  it("preserves the point under the cursor when zooming", () => {
    expect(zoomAt({ x: 30, y: 20, scale: 1 }, { x: 100, y: 80 }, 2)).toEqual({
      x: -40,
      y: -40,
      scale: 2,
    });
  });
  it("clamps zoom and safely handles a zero-sized pane", () => {
    expect(zoomAt({ x: 0, y: 0, scale: 1 }, { x: 0, y: 0 }, 100).scale).toBe(3);
    expect(fitViewport({ w: 500, h: 500 }, 0, 0).scale).toBeGreaterThan(0);
  });
  it("fits negative extents and centers the content", () => {
    const view = fitViewport(
      { w: 200, h: 200, "min-x": -100, "min-y": -50 },
      800,
      600,
    );
    expect(view.x + -100 * view.scale).toBeGreaterThanOrEqual(32);
    expect(view.y + -50 * view.scale).toBeGreaterThanOrEqual(32);
  });
});
