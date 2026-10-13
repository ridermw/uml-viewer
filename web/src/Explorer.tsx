import type { ClassNode, Scene } from "./types";

export function matches(c: ClassNode, query: string): boolean {
  const text = [c.name, c.ns ?? "", ...c.ops.map((op) => op.name ?? op.text)]
    .join(" ")
    .toLowerCase();
  return text.includes(query.toLowerCase().trim());
}

export function Explorer({
  scene,
  selectedId,
  query,
  onSelect,
  onDrill,
}: {
  scene: Scene;
  selectedId: string | null;
  query: string;
  onSelect: (id: string) => void;
  onDrill: (id: string) => void;
}) {
  const groups = scene.packages.map((p) => ({
    id: p.id,
    label: p.label,
    classes: scene.classes.filter((c) => c.package === p.id),
  }));
  const extra = scene.classes.filter(
    (c) => !scene.packages.some((p) => p.id === c.package),
  );
  if (extra.length)
    groups.push({ id: "__components", label: "Components", classes: extra });
  const found = scene.classes.some((c) => matches(c, query));
  return (
    <div data-testid="explorer" className="explorer-list">
      {!found && <p className="empty">No matching classes or methods.</p>}
      {groups.map((group) => {
        const classes = group.classes.filter((c) => matches(c, query));
        return (
          classes.length > 0 && (
            <details key={group.id} open>
              <summary>
                {group.label}
                <span className="count">{classes.length}</span>
              </summary>
              {classes.map((c) => (
                <div
                  key={c.id}
                  className={`explorer-row ${c.id === selectedId ? "selected" : ""}`}
                >
                  <button
                    className="class-select"
                    onClick={() => onSelect(c.id)}
                    aria-pressed={c.id === selectedId}
                  >
                    <span className="class-icon" aria-hidden="true">
                      C
                    </span>
                    <span className="truncate" title={c.ns ?? c.name}>
                      {c.name}
                    </span>
                  </button>
                  {c["drill?"] && (
                    <button
                      className="drill"
                      aria-label={`Open ${c.name}`}
                      onClick={() => onDrill(c.id)}
                    >
                      &gt;
                    </button>
                  )}
                </div>
              ))}
            </details>
          )
        );
      })}
    </div>
  );
}
