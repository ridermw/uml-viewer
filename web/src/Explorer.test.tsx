import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Explorer, matches } from "./Explorer";
import { catalog, library } from "./test/fixtures";

describe("project explorer", () => {
  it("searches names, namespaces and methods", () => {
    expect(matches(catalog, "EXAMPLE")).toBe(true);
    expect(matches(catalog, "find")).toBe(true);
    expect(matches(catalog, "missing")).toBe(false);
  });
  it("selects an actual scene identity", async () => {
    const select = vi.fn();
    render(
      <Explorer
        scene={library.scene}
        selectedId={null}
        query=""
        onSelect={select}
        onDrill={vi.fn()}
      />,
    );
    await userEvent.click(screen.getByRole("button", { name: "Catalog" }));
    expect(select).toHaveBeenCalledWith("d0-catalog");
  });
  it("shows an explicit empty search state", () => {
    render(
      <Explorer
        scene={library.scene}
        selectedId={null}
        query="unknown"
        onSelect={vi.fn()}
        onDrill={vi.fn()}
      />,
    );
    expect(screen.getByText("No matching classes or methods.")).toBeVisible();
  });
});
