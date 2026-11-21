export type Point = [number, number];
export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}
export type Band = "green" | "yellow" | "red" | null;
export type MetricMode = "crap" | "coverage" | "mutation";
export interface Crap {
  mu: number | null;
  max: number | null;
  sigma: number | null;
  n?: number;
}
export interface MetricValues {
  crap: Crap | null;
  coverage: number | null;
  cc: number | null;
  killed: number | null;
  survived: number | null;
  uncovered: number | null;
  sites: number | null;
  invalid?: number | null;
  errors?: number | null;
  incomplete?: boolean | null;
}
export interface Member extends MetricValues {
  text: string;
  name?: string;
  private?: boolean;
}
export interface ClassLine {
  kind: "name" | "stereo" | "rule" | "field" | "op" | "child";
  text: string | null;
  id?: string;
  name?: string;
  "drill?"?: boolean;
}
export interface Port {
  id: string;
  name?: string;
  rect: Rect;
}
export interface ClassNode extends MetricValues {
  id: string;
  name: string;
  rect: Rect;
  package: string | null;
  ns: string | null;
  file: string | null;
  lang: string | null;
  shape: "oval" | null;
  "drill?": boolean;
  fields: Member[];
  ops: Member[];
  lines: ClassLine[];
  "in-ports": Port[];
  "out-ports": Port[];
  crapBand: Band;
  mutationBand: Band;
}
export interface PackageNode {
  id: string;
  label: string;
  rect: Rect;
}
export interface Edge {
  from: string;
  to: string;
  kind: string;
  label: string | null;
  violating: boolean;
  points: Point[];
  strokes: Point[][];
  tip: Point | null;
  behind: Point | null;
}
export interface Scene {
  classes: ClassNode[];
  packages: PackageNode[];
  edges: Edge[];
  size: { w: number; h: number; "min-x"?: number; "min-y"?: number };
  sections: { title: string; "title-y": number }[];
}
export interface Snapshot {
  project: { root: string; diagram: string; title: string };
  revision: string;
  loadedAt: string;
  focus: string[];
  hierarchical: boolean;
  scene: Scene;
}
export interface SourceResult {
  file: string;
  body: string;
  line: number | null;
  lang: string;
  revision: string;
}
