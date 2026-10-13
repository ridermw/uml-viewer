import { render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { Inspector } from "./Inspector";
import { catalog, library } from "./test/fixtures";

it("does not turn missing metrics into zero or a risk grade", () => {
  render(<Inspector node={catalog} snapshot={library} onSelect={vi.fn()} onDrill={vi.fn()}/>);
  expect(screen.getAllByText("N/A").length).toBeGreaterThan(2);
  expect(screen.getByText("Unknown")).toBeVisible();
  expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
});
