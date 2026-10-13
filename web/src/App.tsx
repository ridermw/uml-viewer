import { useEffect, useRef, useState } from "react";
import { message, request } from "./api";
import { Explorer } from "./Explorer";
import { Diagram } from "./Diagram";
import { Inspector } from "./Inspector";
import { OpenProject } from "./OpenProject";
import type { MetricMode, Snapshot } from "./types";
import "./styles.css";

export default function App() {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [metric, setMetric] = useState<MetricMode>("crap");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(true);
  const [open, setOpen] = useState(false);
  const sequence = useRef(0);
  const searchInput = useRef<HTMLInputElement>(null);
  const replace = (data: Snapshot) => {
    setSnapshot(data);
    setSelected((previous) =>
      data.scene.classes.some((c) => c.id === previous) ? previous : null,
    );
  };
  useEffect(() => {
    const controller = new AbortController();
    const id = ++sequence.current;
    request<Snapshot>("/api/document", undefined, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted && id === sequence.current) {
          replace(data);
          setBusy(false);
        }
      })
      .catch((cause) => {
        if (!controller.signal.aborted && id === sequence.current) {
          setError(message(cause));
          setBusy(false);
        }
      });
    return () => controller.abort();
  }, []);
  useEffect(() => {
    const keydown = (event: KeyboardEvent) => {
      const target = event.target;
      if (
        event.key === "/" &&
        !(
          target instanceof HTMLElement &&
          (target.isContentEditable ||
            /INPUT|TEXTAREA|SELECT/.test(target.tagName))
        )
      ) {
        event.preventDefault();
        searchInput.current?.focus();
      }
    };
    document.addEventListener("keydown", keydown);
    return () => document.removeEventListener("keydown", keydown);
  }, []);
  const operation = async (path: string, body: object, close = false) => {
    const id = ++sequence.current;
    setBusy(true);
    setError(null);
    try {
      const data = await request<Snapshot>(path, body);
      if (id === sequence.current) {
        replace(data);
        if (close) setOpen(false);
      }
    } catch (cause) {
      if (id === sequence.current) setError(message(cause));
    } finally {
      if (id === sequence.current) setBusy(false);
    }
  };
  const focus = (parts: string[]) =>
    snapshot &&
    operation("/api/focus", { revision: snapshot.revision, focus: parts });
  const drill = (id: string) => {
    const part = id.split(".").at(-1);
    if (snapshot && part) focus([...snapshot.focus, part]);
  };
  const node = snapshot?.scene.classes.find((c) => c.id === selected);
  const violations =
    snapshot?.scene.edges.filter((e) => e.violating).length ?? 0;
  return (
    <div className="app">
      <header className="titlebar">
        <div aria-hidden="true" className="window-dots">
          <i />
          <i />
          <i />
        </div>
        <h1>UML Viewer</h1>
        <span className="local-badge">LOCAL</span>
      </header>
      <div className="toolbar">
        <span className="project-name">
          {snapshot?.project.title ?? "Project"}
        </span>
        <label className="search">
          <span className="muted">Search</span>
          <input
            ref={searchInput}
            aria-label="Search classes or methods"
            placeholder="Classes, namespaces, or methods"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <kbd>/</kbd>
        </label>
        <button
          className="primary"
          onClick={() => {
            setError(null);
            setOpen(true);
          }}
        >
          Open Project
        </button>
      </div>
      {error && !open && (
        <div className="error-banner" role="alert">
          {error}
        </div>
      )}
      <main className="workspace">
        <aside className="explorer pane">
          <div className="pane-title">
            <h2>Explorer</h2>
          </div>
          <div className="project-summary">
            <strong>{snapshot?.project.title ?? "Loading project"}</strong>
            <p>
              {snapshot?.scene.classes.length ?? 0} classes /{" "}
              {snapshot?.scene.packages.length ?? 0} packages
            </p>
          </div>
          {snapshot && (
            <Explorer
              scene={snapshot.scene}
              selectedId={selected}
              query={query}
              onSelect={setSelected}
              onDrill={drill}
            />
          )}
          <div className="explorer-footer">
            <button
              disabled={!snapshot || busy}
              onClick={() =>
                snapshot &&
                operation("/api/reload", { revision: snapshot.revision })
              }
            >
              Refresh diagram
            </button>
            <p className="small muted">
              EDN diagrams and existing metric snapshots
            </p>
          </div>
        </aside>
        <section className="architecture pane">
          <div className="architecture-header">
            <h2>Architecture</h2>
            <span className="small muted">Read-only workbench</span>
          </div>
          <div className="canvas-toolbar">
            <nav aria-label="Component breadcrumb">
              <button
                disabled={!snapshot || !snapshot.focus.length || busy}
                onClick={() => focus([])}
              >
                {snapshot?.project.title ?? "Project"}
              </button>
              {snapshot?.focus.map((part, i) => (
                <button
                  disabled={busy}
                  key={i}
                  onClick={() => focus(snapshot.focus.slice(0, i + 1))}
                >
                  / {part}
                </button>
              ))}
              {!!snapshot?.focus.length && (
                <button
                  disabled={busy}
                  onClick={() => focus(snapshot.focus.slice(0, -1))}
                >
                  Up
                </button>
              )}
            </nav>
            <label>
              Metric color{" "}
              <select
                aria-label="Metric color"
                value={metric}
                onChange={(e) => setMetric(e.target.value as MetricMode)}
              >
                <option value="crap">CRAP risk</option>
                <option value="coverage">Coverage</option>
                <option value="mutation">Mutation</option>
              </select>
            </label>
          </div>
          {busy && (
            <div className="loading" role="status">
              Loading diagram...
            </div>
          )}
          {snapshot && (
            <Diagram
              scene={snapshot.scene}
              selectedId={selected}
              metric={metric}
              onSelect={setSelected}
              onDrill={drill}
            />
          )}
        </section>
        <aside className="inspector pane">
          <div className="pane-title">
            <h2>Inspector</h2>
            <span className="small muted">{node?.lang ?? ""}</span>
          </div>
          <div className="inspector-content">
            {node && snapshot ? (
              <Inspector
                key={`${snapshot.revision}:${node.id}`}
                node={node}
                snapshot={snapshot}
                onSelect={setSelected}
                onDrill={drill}
              />
            ) : (
              <div className="empty-inspector">
                <span className="class-icon large">C</span>
                <h3>Explore your architecture</h3>
                <p>
                  Select a class to inspect metrics, relationships, and source.
                </p>
              </div>
            )}
          </div>
        </aside>
      </main>
      <footer className="statusbar">
        <span>Local project</span>
        <span>{snapshot?.scene.classes.length ?? 0} classes</span>
        <span>{snapshot?.scene.edges.length ?? 0} relationships</span>
        <span className={violations ? "danger-text" : ""}>
          {violations
            ? `${violations} dependency violations`
            : "No dependency violations"}
        </span>
        <span className="status-time">
          {snapshot
            ? `Loaded ${new Date(snapshot.loadedAt).toLocaleTimeString()}`
            : "Waiting for diagram"}
        </span>
      </footer>
      {open && (
        <OpenProject
          project={snapshot?.project}
          busy={busy}
          error={error}
          onOpen={(root, diagram) =>
            operation("/api/open", { root, diagram }, true)
          }
          onClose={() => {
            setOpen(false);
            setError(null);
          }}
        />
      )}
    </div>
  );
}
