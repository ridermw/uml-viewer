import { defineConfig } from "@playwright/test";

const port = Number(process.env.VIEWER_TEST_PORT ?? 4173);
export default defineConfig({
  testDir: "./e2e",
  workers: 1,
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    viewport: { width: 1536, height: 1024 },
    screenshot: "only-on-failure",
  },
  webServer: {
    command: `npm run build && cd .. && clojure -M:web --port ${port}`,
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
