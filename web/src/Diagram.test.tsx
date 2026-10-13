import { render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { Diagram } from "./Diagram";
import { library } from "./test/fixtures";

it("renders actual class geometry and exposes selection to the keyboard", () => {
  const select = vi.fn();
  render(<Diagram scene={library.scene} selectedId="d0-catalog" metric="crap" onSelect={select} onDrill={vi.fn()}/>);
  expect(screen.getAllByTestId("class-node")).toHaveLength(1);
  expect(screen.getByRole("button", { name: "Catalog" })).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByText("N/A = unknown")).toBeVisible();
});
