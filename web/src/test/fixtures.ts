import type { ClassNode, Snapshot } from "../types";

export const catalog: ClassNode = {
  id: "d0-catalog", name: "Catalog", ns: "example.catalog", file: "src/catalog.py",
  lang: "python", package: "d0-domain", shape: null, "drill?": false,
  rect: { x: 40, y: 40, w: 200, h: 90 }, crap: null, coverage: null, cc: null,
  killed: null, survived: null, uncovered: null, sites: null, crapBand: null, mutationBand: null,
  fields: [], ops: [{
    name: "find", text: "find(isbn)", crap: null, coverage: null, cc: null,
    killed: null, survived: null, uncovered: null, sites: null,
  }],
  lines: [{ kind: "name", text: "Catalog" }, { kind: "rule", text: null }, { kind: "op", text: "find(isbn)" }],
  "in-ports": [], "out-ports": [],
};
export const library: Snapshot = {
  project: { root: "/project", diagram: "/project/d.edn", title: "Example" },
  revision: "revision-1", loadedAt: "2026-10-13T01:00:00Z", focus: [], hierarchical: false,
  scene: {
    classes: [catalog], packages: [{ id: "d0-domain", label: "Domain", rect: { x: 0, y: 0, w: 280, h: 160 } }],
    edges: [], sections: [], size: { w: 300, h: 200 },
  },
};
