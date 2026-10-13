import type { Band, ClassNode, MetricMode } from "./types";

export function percent(value: number | null): string {
  return value === null ? "N/A" : `${Math.round(value * 100)}%`;
}
export function mutationScore(killed: number | null, survived: number | null): number | null {
  return killed === null || survived === null || killed + survived === 0 ? null : killed / (killed + survived);
}
export function metricBand(c: ClassNode, mode: MetricMode): Band {
  if (mode === "crap") return c.crapBand;
  if (mode === "mutation") return c.mutationBand;
  return c.coverage === null ? null : c.coverage >= 0.9 ? "green" : c.coverage >= 0.8 ? "yellow" : "red";
}
export function metricLabel(c: ClassNode, mode: MetricMode): string {
  if (mode === "crap") return c.crap?.mu?.toFixed(1) ?? "N/A";
  return percent(mode === "coverage" ? c.coverage : mutationScore(c.killed, c.survived));
}
