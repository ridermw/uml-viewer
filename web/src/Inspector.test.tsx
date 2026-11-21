import { render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { Inspector } from "./Inspector";
import { catalog, library } from "./test/fixtures";

it("does not turn missing metrics into zero or a risk grade", () => {
  render(
    <Inspector
      node={catalog}
      snapshot={library}
      onSelect={vi.fn()}
      onDrill={vi.fn()}
    />,
  );
  expect(screen.getAllByText("N/A").length).toBeGreaterThan(2);
  expect(screen.getByText("Unknown")).toBeVisible();
  expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
});

it("shows Incomplete instead of 100% when mutation run is incomplete", () => {
  render(
    <Inspector
      node={{ ...catalog, killed: 8, survived: 0, incomplete: true }}
      snapshot={library}
      onSelect={vi.fn()}
      onDrill={vi.fn()}
    />,
  );
  expect(screen.getByText("Incomplete")).toBeVisible();
  expect(screen.queryByText("100%")).not.toBeInTheDocument();
  expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
});
