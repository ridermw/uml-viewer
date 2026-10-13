import { useState } from "react";
import { mutationScore, percent } from "./metrics";
import { useSource } from "./useSource";
import type { ClassNode, Snapshot } from "./types";

export function Inspector({
  node,
  snapshot,
  onSelect,
  onDrill,
}: {
  node: ClassNode;
  snapshot: Snapshot;
  onSelect: (id: string) => void;
  onDrill: (id: string) => void;
}) {
  const [tab, setTab] = useState<"overview" | "source">("overview");
  const [member, setMember] = useState<string | null>(null);
  const source = useSource(
    snapshot.revision,
    node.id,
    member,
    tab === "source",
  );
  const mutation = mutationScore(node.killed, node.survived);
  const relationships = snapshot.scene.edges.filter(
    (e) => e.from === node.id || e.to === node.id,
  );
  const method = (name: string) => {
    setMember(name);
    setTab("source");
  };
  return (
    <>
      <div className="identity">
        <span className="class-icon large">C</span>
        <div>
          <h3 data-testid="inspector-title">{node.name}</h3>
          <code className="muted">{node.ns ?? node.id}</code>
        </div>
      </div>
      {node["drill?"] && (
        <button className="wide" onClick={() => onDrill(node.id)}>
          Open component
        </button>
      )}
      <div className="tabs" role="tablist" aria-label="Inspector">
        <button
          role="tab"
          aria-selected={tab === "overview"}
          onClick={() => setTab("overview")}
        >
          Overview
        </button>
        <button
          role="tab"
          aria-selected={tab === "source"}
          onClick={() => {
            setMember(null);
            setTab("source");
          }}
        >
          Source
        </button>
      </div>
      {tab === "overview" ? (
        <div role="tabpanel">
          <div className="metrics">
            <div className="metric-row">
              <span>Coverage</span>
              <strong>{percent(node.coverage)}</strong>
              {node.coverage !== null && (
                <progress className="coverage" max="1" value={node.coverage} />
              )}
            </div>
            <div className="metric-row">
              <span>Mutation score</span>
              <strong>{percent(mutation)}</strong>
              {mutation !== null && <progress max="1" value={mutation} />}
            </div>
            <div className="metric-row">
              <span>CRAP mean</span>
              <strong>{node.crap?.mu?.toFixed(1) ?? "N/A"}</strong>
              <span className={`badge ${node.crapBand ?? "neutral"}`}>
                {node.crapBand === "red"
                  ? "High risk"
                  : node.crapBand === "yellow"
                    ? "Review"
                    : node.crapBand === "green"
                      ? "Low risk"
                      : "Unknown"}
              </span>
            </div>
            {node.crap && (
              <p className="small muted">
                Risk coloring uses mean + standard deviation.
              </p>
            )}
          </div>
          <section>
            <h4>Methods</h4>
            {node.ops.length ? (
              <table>
                <thead>
                  <tr>
                    <th>Method</th>
                    <th>CC</th>
                    <th>Cov.</th>
                    <th>Survivors</th>
                  </tr>
                </thead>
                <tbody>
                  {node.ops.map((op, i) => (
                    <tr key={`${op.name}-${i}`}>
                      <td>
                        {op.name ? (
                          <button
                            className="text-button"
                            onClick={() => {
                              if (op.name) method(op.name);
                            }}
                          >
                            {op.name}
                          </button>
                        ) : (
                          op.text
                        )}
                      </td>
                      <td>{op.cc ?? "N/A"}</td>
                      <td>{percent(op.coverage)}</td>
                      <td className={op.survived ? "danger-text" : ""}>
                        {op.survived ?? "N/A"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <p className="muted">No methods in this view.</p>
            )}
          </section>
          <section>
            <h4>Relationships</h4>
            {relationships.length ? (
              relationships.map((e, i) => {
                const outgoing = e.from === node.id;
                const id = outgoing ? e.to : e.from;
                const other = snapshot.scene.classes.find((c) => c.id === id);
                return (
                  <button
                    className="relationship"
                    key={i}
                    onClick={() => onSelect(id)}
                  >
                    <span>{other?.name ?? id}</span>
                    <span className="small muted">
                      {outgoing ? "to" : "from"} / {e.kind}
                    </span>
                  </button>
                );
              })
            ) : (
              <p className="muted">No relationships.</p>
            )}
          </section>
          <section>
            <h4>Source</h4>
            <p className="small mono">
              {node.file ?? "Source resolved by namespace when available."}
            </p>
            <button
              className="text-button"
              onClick={() => {
                setMember(null);
                setTab("source");
              }}
            >
              View source
            </button>
          </section>
        </div>
      ) : (
        <div role="tabpanel" className="source-panel">
          <h4>{member ?? node.name}</h4>
          {source.loading && <p role="status">Loading source...</p>}
          {source.error && (
            <p role="alert" className="error">
              {source.error}
            </p>
          )}
          {source.data && (
            <>
              <p className="small mono">
                {source.data.file}
                {source.data.line ? `:${source.data.line}` : ""}
              </p>
              <pre>
                {source.data.body.split("\n").map((line, index) => (
                  <span
                    key={index}
                    className={`source-line ${index + 1 === source.data?.line ? "active-line" : ""}`}
                  >
                    <span className="line-number">{index + 1}</span>
                    {line || " "}
                  </span>
                ))}
              </pre>
            </>
          )}
        </div>
      )}
      <div className="local-note">
        <strong>Local exploration</strong>
        <p>No agent required. Diagrams and source stay on this machine.</p>
      </div>
    </>
  );
}
