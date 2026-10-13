import { expect, test, type Page } from "@playwright/test";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("./fixtures/project", import.meta.url));
async function open(page: Page, diagram: string) {
  await page.getByRole("button", { name: "Open Project", exact: true }).click();
  await page.getByLabel("Project directory", { exact: true }).fill(root);
  await page.getByLabel("EDN diagram", { exact: true }).fill(diagram);
  await page.getByRole("button", { name: "Open diagram", exact: true }).click();
}
test.beforeEach(async ({ page }) => {
  await page.goto("/");
  await expect(page.getByTestId("explorer")).toBeVisible();
});

test("selects real classes and operates the canvas", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await open(page, "diagram.edn");
  await page
    .getByTestId("explorer")
    .getByRole("button", { name: "Catalog", exact: true })
    .click();
  await expect(page.getByTestId("inspector-title")).toHaveText("Catalog");
  await expect(page.getByTestId("class-node")).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await expect(page.getByText("75%", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("80%", { exact: true })).toBeVisible();
  const zoom = page.getByTestId("zoom-level"),
    previous = await zoom.textContent();
  await page.getByRole("button", { name: "Zoom in", exact: true }).click();
  await expect(zoom).not.toHaveText(previous ?? "");
  await page.getByRole("button", { name: "Fit view", exact: true }).click();
  await page.getByLabel("Metric color").selectOption("coverage");
  await expect(page.locator(".canvas-legend")).toContainText("90%+");
  await page.getByLabel("Search classes or methods").fill("find");
  await expect(
    page
      .getByTestId("explorer")
      .getByRole("button", { name: "Catalog", exact: true }),
  ).toBeVisible();
  await page.getByLabel("Search classes or methods").fill("absent");
  await expect(page.getByText("No matching classes or methods.")).toBeVisible();
  expect(errors).toEqual([]);
});

test("inspects actual class and method source", async ({ page }) => {
  await open(page, "diagram.edn");
  await page
    .getByTestId("explorer")
    .getByRole("button", { name: "Catalog", exact: true })
    .click();
  await page.getByRole("button", { name: "find", exact: true }).click();
  await expect(page.locator("pre")).toContainText("return isbn");
  await expect(page.locator(".active-line")).toContainText("def find");
  await page.getByRole("tab", { name: "Source", exact: true }).click();
  await expect(page.locator("pre")).toContainText("class Catalog");
});

test("selects from the SVG and pans without turning a drag into selection", async ({
  page,
}) => {
  await open(page, "diagram.edn");
  await page.getByTestId("class-node").click();
  await expect(page.getByTestId("inspector-title")).toHaveText("Catalog");
  const canvas = page.getByRole("group", { name: "Interactive UML diagram" });
  const bounds = await canvas.boundingBox();
  if (!bounds) throw new Error("Canvas bounds are unavailable.");
  const viewport = page.getByTestId("scene-viewport");
  const previous = await viewport.getAttribute("transform");
  await page.mouse.move(bounds.x + 40, bounds.y + 60);
  await page.mouse.down();
  await page.mouse.move(bounds.x + 90, bounds.y + 110, { steps: 5 });
  await page.mouse.up();
  await expect(viewport).not.toHaveAttribute("transform", previous ?? "");
  await expect(page.getByTestId("inspector-title")).toHaveText("Catalog");
  const zoom = await page.getByTestId("zoom-level").textContent();
  await page.mouse.wheel(0, -180);
  await expect(page.getByTestId("zoom-level")).not.toHaveText(zoom ?? "");
});

test("navigates hierarchy without starting an agent", async ({ page }) => {
  await open(page, "hierarchy.edn");
  await page.getByRole("button", { name: "Open Alpha", exact: true }).click();
  await expect(
    page.getByRole("navigation", { name: "Component breadcrumb" }),
  ).toContainText("alpha");
  await expect(
    page
      .getByTestId("explorer")
      .getByRole("button", { name: "Catalog", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Up", exact: true }).click();
  await expect(
    page
      .getByTestId("explorer")
      .getByRole("button", { name: "Alpha", exact: true }),
  ).toBeVisible();
});

test("keeps the last diagram when opening malformed or missing EDN", async ({
  page,
}) => {
  await open(page, "diagram.edn");
  await open(page, "bad.edn");
  await expect(page.getByRole("alert")).toContainText("EOF");
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByLabel("EDN diagram", { exact: true }).fill("missing.edn");
  await page.getByRole("button", { name: "Open diagram", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("File not found");
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(
    page
      .getByTestId("explorer")
      .getByRole("button", { name: "Catalog", exact: true }),
  ).toBeVisible();
});

test("shows the checked-in library and captures the desktop composition", async ({
  page,
}) => {
  const projectRoot = fileURLToPath(new URL("../..", import.meta.url));
  await page.getByRole("button", { name: "Open Project", exact: true }).click();
  await page.getByLabel("Project directory", { exact: true }).fill(projectRoot);
  await page
    .getByLabel("EDN diagram", { exact: true })
    .fill("examples/library.edn");
  await page.getByRole("button", { name: "Open diagram", exact: true }).click();
  await page
    .getByTestId("explorer")
    .getByRole("button", { name: "LoanService", exact: true })
    .click();
  await expect(page.getByTestId("class-node")).toHaveCount(10);
  await expect(page.getByTestId("inspector-title")).toHaveText("LoanService");
  await expect(page.getByTestId("zoom-level")).toHaveText("90%");
  if (process.env.VIEWER_SCREENSHOT)
    await page.screenshot({
      path: process.env.VIEWER_SCREENSHOT,
      fullPage: true,
    });
  await page.setViewportSize({ width: 740, height: 900 });
  await expect(
    page.getByRole("button", { name: "Open Project", exact: true }),
  ).toBeVisible();
  await expect(page.getByTestId("inspector-title")).toBeVisible();
});
