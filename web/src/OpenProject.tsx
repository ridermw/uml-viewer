import { useState } from "react";
import type { Snapshot } from "./types";

export function OpenProject({ project, busy, error, onOpen, onClose }: {
  project?: Snapshot["project"]; busy: boolean; error: string | null;
  onOpen: (root: string, diagram: string) => void; onClose: () => void;
}) {
  const [root, setRoot] = useState(project?.root ?? ".");
  const [diagram, setDiagram] = useState(project?.diagram ?? "examples/library.edn");
  return <div className="modal-backdrop"><div role="dialog" aria-modal="true" aria-labelledby="open-title" className="modal" onKeyDown={event => {
    if (event.key === "Escape" && !busy) onClose();
    if (event.key === "Tab") {
      const elements = event.currentTarget.querySelectorAll<HTMLElement>("input, button:not(:disabled)");
      const first = elements[0], last = elements[elements.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
  }}>
    <h2 id="open-title">Open project diagram</h2>
    <p className="muted">Choose a local project and an existing EDN diagram. Generate a diagram with the existing IR generator first if needed.</p>
    <form onSubmit={e => { e.preventDefault(); onOpen(root, diagram); }}>
      <label>Project directory<input autoFocus required value={root} onChange={e => setRoot(e.target.value)} /></label>
      <label>EDN diagram<input required value={diagram} onChange={e => setDiagram(e.target.value)} /></label>
      {error && <p role="alert" className="error">{error}</p>}
      <div className="modal-actions"><button type="button" disabled={busy} onClick={onClose}>Cancel</button><button className="primary" disabled={busy} type="submit">{busy ? "Opening..." : "Open diagram"}</button></div>
    </form>
  </div></div>;
}
