import { useEffect, useRef, useState } from "react";
import { metricBand, metricLabel } from "./metrics";
import { fitViewport, zoomAt, type Viewport } from "./viewport";
import type { ClassNode, Edge, MetricMode, Point, Scene } from "./types";

const colors = {
  green: "#198465",
  yellow: "#b77913",
  red: "#c34444",
  neutral: "#8a96a7",
};
const polyline = (points: Point[]) => points.map((p) => p.join(",")).join(" ");

function Arrow({ edge }: { edge: Edge }) {
  const tip = edge.tip ?? edge.points.at(-1);
  const behind = edge.behind ?? edge.points.at(-2);
  if (!tip || !behind) return null;
  const angle =
    (Math.atan2(tip[1] - behind[1], tip[0] - behind[0]) * 180) / Math.PI;
  const hollow = edge.kind === "inheritance" || edge.kind === "implements";
  if (edge.kind === "aggregation" || edge.kind === "composition") {
    const first = edge.points[0],
      next = edge.points[1];
    if (!first || !next) return null;
    const rotation =
      (Math.atan2(next[1] - first[1], next[0] - first[0]) * 180) / Math.PI;
    return (
      <polygon
        transform={`translate(${first.join(" ")}) rotate(${rotation})`}
        points="0,0 8,-5 16,0 8,5"
        fill={edge.kind === "composition" ? "currentColor" : "white"}
      />
    );
  }
  return (
    <path
      transform={`translate(${tip.join(" ")}) rotate(${angle})`}
      d={hollow ? "M -12 -6 L 0 0 L -12 6 Z" : "M -8 -4 L 0 0 L -8 4"}
      fill={hollow ? "white" : "none"}
    />
  );
}

function ClassCard({
  node,
  selected,
  metric,
  onSelect,
  onDrill,
}: {
  node: ClassNode;
  selected: boolean;
  metric: MetricMode;
  onSelect: (id: string) => void;
  onDrill: (id: string) => void;
}) {
  const r = node.rect,
    band = metricBand(node, metric);
  let y = r.y + 16;
  return (
    <g
      data-class-id={node.id}
      data-testid="class-node"
      className={`class-node ${selected ? "selected-node" : ""}`}
      role="button"
      aria-label={node.name}
      aria-pressed={selected}
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onSelect(node.id);
        }
        if (e.key === "ArrowRight" && node["drill?"]) {
          e.preventDefault();
          onDrill(node.id);
        }
      }}
    >
      <title>{`${node.name}: ${metricLabel(node, metric)}${node["drill?"] ? " / drillable component" : ""}`}</title>
      {node.shape === "oval" ? (
        <>
          <ellipse
            cx={r.x + r.w / 2}
            cy={r.y + r.h / 2}
            rx={r.w / 2}
            ry={r.h / 2}
            fill="white"
            stroke={selected ? "#2563eb" : "#bcc7d4"}
          />
          <text x={r.x + r.w / 2} y={r.y + r.h / 2 + 4} textAnchor="middle">
            {node.name}
          </text>
        </>
      ) : (
        <>
          <rect
            x={r.x}
            y={r.y}
            width={r.w}
            height={r.h}
            rx="5"
            fill="white"
            stroke={selected ? "#2563eb" : "#aebbc9"}
            strokeWidth={selected ? 2 : 1}
          />
          <rect
            x={r.x}
            y={r.y}
            width="3"
            height={r.h}
            rx="2"
            fill={colors[band ?? "neutral"]}
          />
          {node.lines.map((line, i) => {
            if (line.kind === "rule") {
              const ly = y + 2;
              y += 12;
              return (
                <line
                  key={i}
                  x1={r.x + 1}
                  x2={r.x + r.w - 1}
                  y1={ly}
                  y2={ly}
                  stroke="#e3e8ef"
                />
              );
            }
            const ly = y + 11;
            y += 18;
            return (
              <text
                key={i}
                x={r.x + 12}
                y={ly}
                className={line.kind === "name" ? "node-title" : "node-member"}
              >
                {line.text}
              </text>
            );
          })}
          <text
            x={r.x + r.w - 2}
            y={r.y - 7}
            textAnchor="end"
            fill={colors[band ?? "neutral"]}
            className="node-metric"
          >
            {metricLabel(node, metric)}
          </text>
        </>
      )}
      {[...node["in-ports"], ...node["out-ports"]].map((port, i) => {
        const p = port.rect,
          above = p.y < r.y;
        return (
          <g key={i}>
            <line
              x1={r.x + r.w / 2}
              y1={above ? r.y : r.y + r.h}
              x2={p.x + p.w / 2}
              y2={above ? p.y + p.h : p.y}
              stroke="#8a96a7"
            />
            <rect
              x={p.x}
              y={p.y}
              width={p.w}
              height={p.h}
              rx="3"
              fill="#f7f9fc"
              stroke="#b7c3d1"
            />
            <text x={p.x + 5} y={p.y + 13} className="node-member">
              {port.name ?? port.id}
            </text>
          </g>
        );
      })}
    </g>
  );
}

export function Diagram({
  scene,
  selectedId,
  metric,
  onSelect,
  onDrill,
}: {
  scene: Scene;
  selectedId: string | null;
  metric: MetricMode;
  onSelect: (id: string) => void;
  onDrill: (id: string) => void;
}) {
  const host = useRef<HTMLDivElement>(null),
    svg = useRef<SVGSVGElement>(null);
  const [dimensions, setDimensions] = useState({ w: 0, h: 0 });
  const [view, setView] = useState<Viewport>({ x: 0, y: 0, scale: 1 });
  const initialized = useRef<Scene | null>(null);
  const gesture = useRef<{
    x: number;
    y: number;
    view: Viewport;
    id: string | null;
    moved: boolean;
  } | null>(null);
  const lastClass = useRef<string | null>(null);
  useEffect(() => {
    const element = host.current;
    if (!element) return;
    const measure = () => {
      const r = element.getBoundingClientRect();
      if (r.width && r.height) setDimensions({ w: r.width, h: r.height });
    };
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (
      initialized.current !== scene &&
      dimensions.w > 64 &&
      dimensions.h > 64
    ) {
      setView(fitViewport(scene.size, dimensions.w, dimensions.h));
      initialized.current = scene;
    }
  }, [scene, dimensions]);
  useEffect(() => {
    const node = scene.classes.find((c) => c.id === selectedId);
    if (!node || !dimensions.w || !dimensions.h) return;
    setView((previous) => {
      const r = node.rect;
      const x = r.x * previous.scale + previous.x,
        y = r.y * previous.scale + previous.y;
      const visible =
        x >= 20 &&
        y >= 20 &&
        x + r.w * previous.scale <= dimensions.w - 20 &&
        y + r.h * previous.scale <= dimensions.h - 80;
      if (visible && previous.scale >= 0.75) return previous;
      const scale = Math.max(0.9, previous.scale);
      return {
        scale,
        x: dimensions.w / 2 - (r.x + r.w / 2) * scale,
        y: dimensions.h / 2 - (r.y + r.h / 2) * scale,
      };
    });
  }, [selectedId, scene, dimensions]);
  useEffect(() => {
    const element = svg.current;
    if (!element) return;
    const wheel = (event: WheelEvent) => {
      event.preventDefault();
      const rect = element.getBoundingClientRect();
      setView((previous) =>
        zoomAt(
          previous,
          { x: event.clientX - rect.left, y: event.clientY - rect.top },
          Math.exp(-event.deltaY * 0.002),
        ),
      );
    };
    element.addEventListener("wheel", wheel, { passive: false });
    return () => element.removeEventListener("wheel", wheel);
  }, []);
  const fit = () =>
    setView(fitViewport(scene.size, dimensions.w, dimensions.h));
  const zoom = (factor: number) =>
    setView((previous) =>
      zoomAt(previous, { x: dimensions.w / 2, y: dimensions.h / 2 }, factor),
    );
  const violations = scene.edges.filter((e) => e.violating).length;
  return (
    <div className="diagram" ref={host}>
      <svg
        ref={svg}
        role="group"
        aria-label="Interactive UML diagram"
        className="diagram-svg"
        onPointerDown={(event) => {
          if (event.button !== 0) return;
          const target =
            event.target instanceof Element
              ? event.target.closest("[data-class-id]")
              : null;
          const id = target?.getAttribute("data-class-id") ?? null;
          gesture.current = {
            x: event.clientX,
            y: event.clientY,
            view,
            id,
            moved: false,
          };
          event.currentTarget.setPointerCapture?.(event.pointerId);
        }}
        onPointerMove={(event) => {
          const start = gesture.current;
          if (!start) return;
          const dx = event.clientX - start.x,
            dy = event.clientY - start.y;
          if (Math.hypot(dx, dy) > 4) start.moved = true;
          if (start.moved)
            setView({
              ...start.view,
              x: start.view.x + dx,
              y: start.view.y + dy,
            });
        }}
        onPointerUp={(event) => {
          const start = gesture.current;
          if (start && !start.moved && start.id) onSelect(start.id);
          lastClass.current = start && !start.moved ? start.id : null;
          gesture.current = null;
          if (event.currentTarget.hasPointerCapture?.(event.pointerId))
            event.currentTarget.releasePointerCapture(event.pointerId);
        }}
        onPointerCancel={() => {
          gesture.current = null;
        }}
        onDoubleClick={() => {
          const node = scene.classes.find((c) => c.id === lastClass.current);
          if (node?.["drill?"]) onDrill(node.id);
        }}
      >
        <g
          data-testid="scene-viewport"
          transform={`translate(${view.x} ${view.y}) scale(${view.scale})`}
        >
          {scene.sections.map((s, i) => (
            <text
              key={i}
              x="40"
              y={s["title-y"] + 20}
              className="section-title"
            >
              {s.title}
            </text>
          ))}
          {scene.packages.map((p) => (
            <g key={p.id}>
              <rect
                x={p.rect.x}
                y={p.rect.y}
                width={p.rect.w}
                height={p.rect.h}
                rx="8"
                fill="#f4f7fb"
                stroke="#dae2ec"
              />
              <text
                x={p.rect.x + 12}
                y={p.rect.y + 21}
                className="package-title"
              >
                {p.label}
              </text>
            </g>
          ))}
          {scene.edges.map((edge, i) => {
            const midpoint = edge.points[Math.floor(edge.points.length / 2)];
            const dashed =
              edge.kind === "dependency" || edge.kind === "implements";
            return (
              <g
                key={i}
                data-testid="edge"
                className={`edge ${edge.violating ? "violating-edge" : ""}`}
                fill="none"
                stroke="currentColor"
                strokeWidth="1.3"
              >
                {(edge.strokes.length ? edge.strokes : [edge.points]).map(
                  (points, n) => (
                    <polyline
                      key={n}
                      points={polyline(points)}
                      strokeDasharray={
                        dashed || edge.violating ? "6 4" : undefined
                      }
                    />
                  ),
                )}
                <Arrow edge={edge} />
                {edge.label && midpoint && (
                  <text
                    x={midpoint[0] + 6}
                    y={midpoint[1] - 6}
                    className="edge-label"
                    fill="currentColor"
                    stroke="none"
                  >
                    {edge.label}
                  </text>
                )}
              </g>
            );
          })}
          {scene.classes.map((c) => (
            <ClassCard
              key={c.id}
              node={c}
              selected={c.id === selectedId}
              metric={metric}
              onSelect={onSelect}
              onDrill={onDrill}
            />
          ))}
        </g>
      </svg>
      {!scene.classes.length && (
        <div className="canvas-empty">
          <h3>No classes in this view</h3>
          <p>Open another diagram or navigate to a component with classes.</p>
        </div>
      )}
      {!!violations && (
        <div className="violation-note">
          {violations} dependency violation{violations === 1 ? "" : "s"}
        </div>
      )}
      <div className="canvas-legend">
        <span className="legend-dot green-dot" />
        {metric === "crap" ? "Low" : metric === "coverage" ? "90%+" : ">90%"}
        <span className="legend-dot yellow-dot" />
        {metric === "crap"
          ? "Review"
          : metric === "coverage"
            ? "80-89%"
            : "81-90%"}
        <span className="legend-dot red-dot" />
        {metric === "crap"
          ? "High"
          : metric === "coverage"
            ? "<80%"
            : "80% or less"}
        <span className="small muted">N/A = unknown</span>
      </div>
      <div className="canvas-controls">
        <button aria-label="Zoom out" onClick={() => zoom(1 / 1.2)}>
          -
        </button>
        <span data-testid="zoom-level">{Math.round(view.scale * 100)}%</span>
        <button aria-label="Zoom in" onClick={() => zoom(1.2)}>
          +
        </button>
        <button onClick={fit}>Fit view</button>
      </div>
      <button
        className="minimap"
        aria-label="Fit diagram overview"
        onClick={fit}
      >
        <svg viewBox={`0 0 ${scene.size.w} ${scene.size.h}`} aria-hidden="true">
          {scene.classes.map((c) => (
            <rect
              key={c.id}
              {...{
                x: c.rect.x,
                y: c.rect.y,
                width: c.rect.w,
                height: c.rect.h,
              }}
              fill={colors[metricBand(c, metric) ?? "neutral"]}
              opacity=".6"
            />
          ))}
        </svg>
      </button>
    </div>
  );
}
