# Local Web Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the approved three-pane browser viewer using real project diagrams, metrics, and source without requiring an agent.

**Architecture:** A new loopback-only Clojure HTTP entry point serves the built React application and a same-origin JSON API. Clojure retains document normalization, hierarchical composition, metrics, and source extraction; React renders the existing layout as SVG and owns selection and viewport state. The existing Quil viewer remains unchanged.

**Tech Stack:** Java 21, existing Clojure 1.12/Speclj, JDK HttpServer, data.json 2.5.1, React 19.3, TypeScript 7, Vite 8.3, Vitest 5, React Testing Library, Playwright 1.63.

**Spec:** `docs/superpowers/specs/2026-10-13-web-viewer-design.md`

## Global Constraints

- "Basic exploration must not require Grok or another agent."
- "No project data is uploaded to a remote service."
- "Do not change the existing `:run`, `:uml-viewer`, or restart behavior."
- "Missing values must remain null."
- "Bind only to loopback."
- "Diagram/source reads must remain within the chosen project, including after symlink resolution."
- "Parse EDN as data, never evaluate it."
- "Do not run shell commands from API input."
- "Do not return an empty successful diagram in place of an error."
- "Runtime operation does not depend on a CDN, remote font, telemetry service, or Vite dev server."
- Use Node 22.22.2 or newer within the Node 22 release line for the selected frontend test dependencies. The local Node 22.23.1 satisfies this requirement.
- Missing metrics are neutral in this interface even though the existing core's band helpers classify missing scores as red; guard null before calling those helpers.
- Every commit includes `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>` and `Copilot-Session: 6e3eb06f-b6a1-451b-beec-4f9d005db673`.

## File map

| File | Responsibility |
| --- | --- |
| `src/uml_viewer/web/paths.clj` | Canonical project-confined file resolution and size limits |
| `src/uml_viewer/web/model.clj` | Document state, hierarchy navigation, wire representation |
| `src/uml_viewer/web/source.clj` | Confined source lookup and registered extractor calls |
| `src/uml_viewer/web/server.clj` | HTTP, authentication, static assets, structured errors |
| `src/uml_viewer/main/web_viewer.clj` | Argument parsing and server lifecycle |
| `spec/uml_viewer/web/paths_spec.clj` | Root, traversal, symlink, and limit behavior |
| `spec/uml_viewer/web/model_spec.clj` | Real scene/identity/metric/hierarchy behavior |
| `spec/uml_viewer/web/source_spec.clj` | Supported source extraction and error behavior |
| `spec/uml_viewer/web/server_spec.clj` | Real HTTP contract and confinement |
| `spec/uml_viewer/main/web_viewer_spec.clj` | CLI argument validation |
| `web/package.json`, `web/package-lock.json` | Frontend scripts and reproducible dependencies |
| `web/index.html`, `web/vite.config.ts`, `web/tsconfig.json` | Frontend entry/build/test configuration |
| `web/src/main.tsx`, `web/src/App.tsx`, `web/src/styles.css` | App mount, document interactions, visual system |
| `web/src/types.ts`, `web/src/api.ts` | Wire types and authenticated API calls |
| `web/src/metrics.ts`, `web/src/viewport.ts` | Pure presentation and SVG viewport math |
| `web/src/Explorer.tsx`, `web/src/Diagram.tsx`, `web/src/Inspector.tsx` | Three panes |
| `web/src/OpenProject.tsx` | Explicit project/diagram selection dialog |
| `web/src/useSource.ts` | Cancelable source request lifecycle |
| `web/src/test/setup.ts`, `web/src/test/fixtures.ts` | Browser-unit test setup and typed fixtures |
| `web/src/*.test.ts`, `web/src/*.test.tsx` | Targeted unit/component tests |
| `web/playwright.config.ts`, `web/e2e/viewer.spec.ts` | Built-server browser verification |
| `web/e2e/fixtures/` | Small flat/hierarchical/source/error test projects |
| `deps.edn`, `.gitignore`, `README.md` | Additive CLI alias, generated-file exclusions, usage |

## Shared contract

### Clojure state and functions

`paths/root! [path] -> java.nio.file.Path` resolves an existing directory.

`paths/file! [root path limit] -> java.nio.file.Path` resolves an existing
regular file, follows symlinks, requires `resolved.startsWith(root)`, and
enforces the byte limit. Relative paths resolve against `root`.

`model/open-document [root diagram] -> state` returns:

```clojure
{:root canonical-root-path
 :diagram canonical-diagram-path
 :raw normalized-or-hierarchical-document
 :focus []
 :revision (str (java.util.UUID/randomUUID))}
```

`model/focus-document [state segments] -> state` accepts a vector of
namespace segments and permits only a reachable hierarchy focus.
For flat documents only `[]` is valid.

`model/reload-document [state] -> state` rereads the diagram and updates its
revision, retaining focus only if still reachable.

`model/snapshot [state] -> wire-ready map` compiles the current view against
the explicit `:root`, not `overlay/metrics-root`'s working-directory fallback.

`model/wire [value] -> JSON-compatible value` recursively turns keywords
into full strings without their leading colon. It retains namespace
components; `(name :demo/Class)` is not an acceptable identity conversion.

`source/read-member [state class-id member-name] -> wire-ready source map`
finds a class in the current compiled scene, validates its source path
before reading, and uses registered extractors on the confined text.

All expected errors use `ex-info` with
`{:status integer :code string}` and a human-readable message.

### JSON snapshot

```ts
export type Point = [number, number];
export interface Rect { x: number; y: number; w: number; h: number }
export interface Crap {
  mu: number | null; max: number | null; sigma: number | null;
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
  id: string; name?: string; rect: Rect;
}
export interface ClassNode extends MetricValues {
  id: string; name: string; rect: Rect;
  package: string | null;
  ns: string | null; file: string | null; lang: string | null;
  shape: "oval" | null;
  "drill?": boolean;
  fields: Member[]; ops: Member[]; lines: ClassLine[];
  "in-ports": Port[]; "out-ports": Port[];
  crapBand: "green" | "yellow" | "red" | null;
  mutationBand: "green" | "yellow" | "red" | null;
}
export interface PackageNode {
  id: string; label: string; rect: Rect;
}
export interface Edge {
  from: string; to: string; kind: string;
  label: string | null; violating: boolean;
  points: Point[]; strokes: Point[][];
  tip: Point | null; behind: Point | null;
}
export interface Scene {
  classes: ClassNode[]; packages: PackageNode[]; edges: Edge[];
  size: { w: number; h: number; "min-x"?: number; "min-y"?: number };
  sections: { title: string; "title-y": number }[];
}
export interface Snapshot {
  project: { root: string; diagram: string; title: string };
  revision: string;
  focus: string[];
  hierarchical: boolean;
  scene: Scene;
  loadedAt: string;
}
export interface SourceResult {
  file: string; body: string; line: number | null;
  lang: string; revision: string;
}
export interface ApiErrorBody {
  error: { code: string; message: string };
}
```

Project-relative source paths are displayed; the server may retain canonical
paths internally. Class/member nullable properties are emitted explicitly;
an operation supplied only as text has no invented method name.
Project opening, focus changes, and reloads produce a new revision.

### HTTP endpoints

| Method/path | Request | Response |
| --- | --- | --- |
| `GET /api/document` | None | Snapshot |
| `POST /api/open` | `{root, diagram}` | Snapshot |
| `POST /api/focus` | `{revision, focus}` | Snapshot |
| `POST /api/reload` | `{revision}` | Snapshot |
| `POST /api/source` | `{revision, classId, member}` | SourceResult |

Every API request requires `X-Viewer-Token`. A revision mismatch returns
409 `stale-document`. Requests from the frontend use its exact origin.
Unexpected Host/Origin or bad token returns 403. Unknown routes return 404,
wrong methods return 405, malformed JSON/input returns 400, confined-path
violations return 403, missing files/members return 404, invalid EDN or
unsupported source extraction returns 422, oversized content returns 413,
and unexpected failures return 500 with a generic message and server log.

Use a maximum request body of 65,536 bytes, diagram size of 8,388,608 bytes,
and source size of 1,048,576 bytes. The API never accepts a source path.

## Task 1: Confined document loading and wire snapshots

**Files:** Create `web/paths.clj`, `web/model.clj`, and their specs under
the Clojure directories listed above.

**Consumes:** Existing `ir/load-document`, `document/compile-view`,
`hierarchy/nodes-at`, `config/crap-risk`, and band functions.

**Produces:** `root!`, `file!`, `open-document`, `snapshot`,
`focus-document`, `reload-document`, and `wire`.

- [ ] **Step 1: Add failing specs for real documents, identities, and nulls.**

```clojure
(it "keeps namespaced identities lossless on the wire"
  (should= {"id" "demo/Class" "coverage" nil}
           (model/wire {:id :demo/Class :coverage nil})))

(it "loads a real flat document without waiting for an agent"
  (let [state (model/open-document "." "examples/library.edn")
        response (model/snapshot state)]
    (should= "Lending library" (get-in response ["project" "title"]))
    (should (seq (get-in response ["scene" "classes"])))
    (should (every? string?
                    (map #(get % "id")
                         (get-in response ["scene" "classes"]))))))
```

Use a temporary project fixture with a minimal EDN class whose metrics
are omitted; assert coverage/CRAP/counts serialize as null and both bands
are null. Add fixtures for duplicated classes, unknown edge targets, and
malformed EDN. Add a hierarchical fixture with `:alpha` and `:alpha.beta`;
assert focus `["alpha"]` exposes Beta and invalid focus returns 400.
Add real-path tests for `../`, an outside-root symlink, and an oversized file.
Test a diagram selected under another project does not load this repository's
`.metrics`.

- [ ] **Step 2: Run the red test cycle.**

Run from the repository:
`clojure -M:spec spec/uml_viewer/web/paths_spec.clj spec/uml_viewer/web/model_spec.clj`.
Expected initially: unresolved new namespaces/functions.

- [ ] **Step 3: Implement confinement and DTO projection.**

The key identity and path algorithms are:

```clojure
(defn wire [value]
  (cond
    (keyword? value) (subs (str value) 1)
    (map? value) (into {} (map (fn [[k v]] [(wire k) (wire v)]) value))
    (sequential? value) (mapv wire value)
    (set? value) (mapv wire value)
    :else value))

(defn file! [^java.nio.file.Path root path limit]
  (let [candidate (.resolve root (str path))
        resolved (.toRealPath candidate
                              (make-array java.nio.file.LinkOption 0))]
    (when-not (.startsWith resolved root)
      (throw (ex-info "File is outside the selected project."
                      {:status 403 :code "outside-project"})))
    (when-not (java.nio.file.Files/isRegularFile
                resolved (make-array java.nio.file.LinkOption 0))
      (throw (ex-info "Expected a regular file."
                      {:status 400 :code "not-file"})))
    (when (> (java.nio.file.Files/size resolved) limit)
      (throw (ex-info "File exceeds the size limit."
                      {:status 413 :code "file-too-large"})))
    resolved))
```

Translate `NoSuchFileException` to the documented 404; validate `root!`
is a directory after `.toRealPath`. Preserve unexpected I/O exceptions
for the HTTP error logger rather than calling them missing files.

For a snapshot, call `compile-view` using the canonical project root and
current focus. Project the existing scene into the shared contract:
exclude the keyword-keyed lookup maps; supply absent arrays as empty;
supply absent nullable metrics/source metadata as null. Preserve `:lines`,
class geometry, shapes, ports, sections, edge strokes/tangents, and
explicit `:violating`. Do not derive violations from rank or direction.

CRAP band uses `config/crap-risk` (mean plus sigma), not the displayed mean.
Mutation band uses known killed/survived counts and a positive denominator,
not fabricated counts or the core's missing-function gap penalty:

```clojure
(defn executed-mutation-score [{:keys [killed survived]}]
  (when (and (some? killed) (some? survived)
             (pos? (+ killed survived)))
    (/ (double killed) (+ killed survived))))
```

Validate focus by walking reachable hierarchy nodes, converting only
accepted namespace segments into keywords. Reload keeps reachable focus,
otherwise resets to `[]`; it must not hide malformed input.

- [ ] **Step 4: Run focused and dependent regression specs.**

Run:
`clojure -M:spec spec/uml_viewer/web spec/uml_viewer/application/document_spec.clj spec/uml_viewer/application/overlay_spec.clj spec/uml_viewer/engine`.
Expected: no failures and no pending new cases.

- [ ] **Step 5: Commit the model deliverable with required trailers.**

Stage only the new model/path namespaces and their specs.
Commit message: `feat: expose confined web viewer document snapshots`.

## Task 2: Confined class and method source inspection

**Files:** Create `web/source.clj` and `web/source_spec.clj`.

**Consumes:** Task 1 state/scene, `paths/file!`, registered `LanguageSource`
implementations, and `detail/member-ident`.

**Produces:** `source/read-member [state class-id member-name]`.

- [ ] **Step 1: Write source tests before implementation.**

Create a temporary root containing this file:

```python
class CheckoutService:
    def checkout(self, patron, isbn):
        return isbn
```

Give the loaded class `:ns "application.checkout"`, `:lang :python`, and
`:file "src/application/checkout.py"`. Assert class inspection returns the
entire text, and method inspection returns line 2. Unknown class/member,
missing file, unsupported language, outside-root path, and symlink escape
each return their documented error without reading outside-project text.
Add Clojure lookup with namespace `example.checkout` and
`src/example/checkout.clj`, when `:file` is absent.
Add a source file larger than 1 MiB and assert 413.

- [ ] **Step 2: Run `clojure -M:spec spec/uml_viewer/web/source_spec.clj`.**

Expected initially: new namespace/function unavailable.

- [ ] **Step 3: Implement source resolution before text access.**

Find the requested wire ID in the current scene and retain its original
class identity for `detail/member-ident`. Register existing Clojure, Python,
Rust, and TypeScript extractors by requiring their namespaces.
Resolve `:file` against the explicit root. If Clojure has only `:ns`, derive
the three project-root candidates `.clj`, `.cljc`, `.cljs`:

```clojure
(defn clojure-candidates [ns-name]
  (let [stem (-> ns-name
                 (clojure.string/replace "-" "_")
                 (clojure.string/replace "." "/"))]
    (mapv #(str "src/" stem %) [".clj" ".cljc" ".cljs"])))
```

Call `file!` before `slurp`. Do not use `member-source` as an unchecked
shortcut: it reads a path before the web adapter can enforce confinement.
Reuse the protocol's `extract` and `start-line` directly on the validated
text. A named member must appear in the class's real operation list and
must be found by the extractor; class-only inspection needs no extraction.
Return the project-relative file path, full body, selected line, language,
and current revision.

- [ ] **Step 4: Run all new web specs and existing language source specs.**

Run:
`clojure -M:spec spec/uml_viewer/web spec/uml_viewer/clojure_language spec/uml_viewer/python_language spec/uml_viewer/rust_language spec/uml_viewer/typescript_language`.

- [ ] **Step 5: Commit with message `feat: add project-confined source inspection`.**

Include required trailers.

## Task 3: Same-origin HTTP server and separate CLI

**Files:** Create `web/server.clj`, `main/web_viewer.clj`, server/CLI specs;
modify `deps.edn`.

**Consumes:** Tasks 1-2 APIs and shared endpoint contract.

**Produces:** `server/start! [options] -> {:server HttpServer :origin string :stop! fn}`,
`server/stop! [server-handle]`, and CLI `parse-args` / `-main`.
Options contain `:root`, `:diagram`, `:port`, and `:assets`.

- [ ] **Step 1: Add the dependency/alias needed by this deliverable.**

Keep the existing aliases unchanged and add:

```clojure
:web {:extra-deps {org.clojure/data.json {:mvn/version "2.5.1"}}
      :main-opts ["-m" "uml-viewer.main.web-viewer"]}
```

Test using `-M:web:spec`, not `-M:spec:web`; the final alias must supply
Speclj's main options.

- [ ] **Step 2: Add failing real-HTTP and CLI specs.**

Start with port 0 and a temporary assets directory containing:

```html
<!doctype html>
<meta name="viewer-token" content="__VIEWER_TOKEN__">
<div id="root"></div>
```

Use JDK `HttpClient` to GET `/`, extract the bootstrap token, then call
`/api/document` with `X-Viewer-Token`. Assert a real scene, JSON content
type, cache prevention, and server address `127.0.0.1`.
Assert missing/wrong token, foreign Origin, and unexpected Host return
403. Test every endpoint and error code from the shared contract.
Opening a malformed diagram must leave the last good server state intact.
Source requests with a prior revision must return 409.
Test unknown flags, nonnumeric/out-of-range ports, help, and missing flag
values. Stop each server in `finally` so tests do not leave processes.

- [ ] **Step 3: Run the red cycle.**

Run:
`clojure -M:web:spec spec/uml_viewer/web/server_spec.clj spec/uml_viewer/main/web_viewer_spec.clj`.

- [ ] **Step 4: Implement bounded transport and lifecycle.**

Bind `HttpServer` to `new InetSocketAddress("127.0.0.1", port)`.
Generate 32 random bytes with `SecureRandom`, encode Base64 URL-safe without
padding, and inject only that alphabet into the bootstrap marker.
Reject Host values not equal to the actual `127.0.0.1:port`.
Allow no Origin header for local nonbrowser clients; when present it must
equal the printed origin. Do not add CORS headers.
Compare token bytes with `MessageDigest/isEqual`.

Read body streams with `readNBytes(65537)` and reject the extra byte.
Parse JSON without interning arbitrary user-supplied keys. Validate required
string fields, revision, focus-array values, and allowed operation inputs.
Synchronize state-changing operations; update the atom only after the
candidate document and snapshot compile successfully.
Return `{"error":{"code":...,"message":...}}` for expected exceptions.
Log unexpected exceptions via `domain.log/log-exception!` and send a
generic 500, never raw stack traces.

Serve `/` and `/index.html` from the confined build directory with token
injection. Other static reads also require canonical containment.
Use correct content types for `.js`, `.css`, `.svg`, `.png`, `.ico`, `.html`.
Send `Cache-Control: no-store` for bootstrap/API and `nosniff` everywhere.
Refuse unsupported routes rather than turning API errors into an HTML
success response. Add a restrictive same-origin CSP and frame rejection.

CLI defaults: root `.`, diagram `examples/library.edn`, assets `web/dist`,
port `0`. Missing build assets produce an actionable build instruction.
Print the origin, keep the process attached, and register a shutdown hook
that stops the server/executor. Do not open a GUI or start an agent.

- [ ] **Step 5: Run new HTTP/model/source specs together.**

Run:
`clojure -M:web:spec spec/uml_viewer/web spec/uml_viewer/main/web_viewer_spec.clj`.
Expected: every new case passes; normal API operation does not load Quil.

- [ ] **Step 6: Commit `feat: serve the local web viewer API`.**

Include required trailers.

## Task 4: Typed three-pane application and real inspection

**Files:** Create frontend manifest/lockfile/config/entry, `types.ts`,
`api.ts`, `metrics.ts`, `Explorer.tsx`, `Inspector.tsx`, `useSource.ts`,
`OpenProject.tsx`, `App.tsx`, `styles.css`, and focused tests.
Modify `.gitignore`.

**Consumes:** Task 3 same-origin endpoints and the shared Snapshot contract.

**Produces:** A built application with real explorer/selection/metrics/
source/open/reload behavior. The central pane initially shows a real class
list; Task 5 replaces only that rendering surface with SVG.

- [ ] **Step 1: Add a manifest and configuration for this deliverable.**

Use these scripts:

```json
{
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "typecheck": "tsc --noEmit",
    "build": "npm run typecheck && vite build",
    "test": "vitest run",
    "test:e2e": "playwright test"
  }
}
```

Runtime dependencies: `react@19.3.0`, `react-dom@19.3.0`.
Dev dependencies: `typescript@7.0.2`, `vite@8.3.2`,
`@vitejs/plugin-react@6.1.2`, `vitest@5.0.3`, `jsdom@30.1.2`,
`@types/react@19.3.0`, `@types/react-dom@19.3.0`,
`@testing-library/react@16.3.3`, `@testing-library/user-event@14.6.7`,
`@testing-library/jest-dom@7.0.1`, and `@playwright/test@1.63.0`.
Add Node types at the registry's compatible stable release if required by
the Vite configuration typecheck.

Now run the package manager to install and write the lockfile. Configure
Vite's React plugin, Vitest's jsdom/setup file, strict TypeScript with
ES2022/ESNext/Bundler/react-jsx, and no emitted TypeScript build artifacts.
Ignore `web/node_modules/`, `web/dist/`, `web/test-results/`, and
`web/playwright-report/`.

`index.html` must contain the token meta marker and the `root` element.
Mount `App` with `createRoot`; do not fetch in render or add remote fonts.

- [ ] **Step 2: Add failing pure and component tests.**

```ts
it("does not manufacture metrics from missing data", () => {
  expect(percent(null)).toBe("N/A");
  expect(mutationScore(null, null)).toBeNull();
  expect(mutationScore(0, 0)).toBeNull();
  expect(mutationScore(8, 2)).toBe(0.8);
  expect(percent(0)).toBe("0%");
});

it("selects a real class from the explorer", async () => {
  const onSelect = vi.fn();
  render(<Explorer scene={library.scene} selectedId={null}
    query="" onSelect={onSelect} onDrill={vi.fn()} />);
  await userEvent.click(screen.getByRole("button", {name: "Catalog"}));
  expect(onSelect).toHaveBeenCalledWith("d0-catalog");
});
```

Use complete typed fixtures, not `as any`. Test namespace/method matching,
no-results search, relationships, null badges, known zero counts, source
404, aborted/out-of-order source responses, open-dialog validation,
preservation of last good snapshot on failure, and clearing selections
removed by reload. Assert the status bar uses actual scene counts.

- [ ] **Step 3: Run the red cycle.**

Run from `web/`: `npm test`.
Expected initially: missing new component/function implementations.

- [ ] **Step 4: Implement the API client, components, and interaction state.**

Create the exact shared types. `api.ts` reads the token meta element once,
fails visibly if missing, and sends authenticated same-origin requests:

```ts
export async function request<T>(
  path: string, init: RequestInit, token: string
): Promise<T> {
  const response = await fetch(path, {
    ...init,
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      "X-Viewer-Token": token,
      ...init.headers,
    },
  });
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.error?.message ?? `Request failed (${response.status})`);
  }
  return data as T;
}
```

The type assertion is confined to the validated server/client boundary;
do not scatter unsafe casts through components. Preserve structured code
information in a dedicated `ApiError` when distinguishing stale-document
errors. Do not convert any error into an empty successful Snapshot.

Pure metric helpers:

```ts
export function percent(value: number | null): string {
  return value === null ? "N/A" : `${Math.round(value * 100)}%`;
}
export function mutationScore(
  killed: number | null, survived: number | null
): number | null {
  if (killed === null || survived === null || killed + survived === 0) {
    return null;
  }
  return killed / (killed + survived);
}
```

`App` owns Snapshot, selected ID, query, metric mode, open dialog, and
document-request error/loading state. GET the initial snapshot in an effect;
abort on cleanup. Use a request sequence guard for overlapping document
operations. Source requests use their own effect/AbortController and
revision/class/member key; reject stale results even if cancellation races.

Explorer groups classes by real packages, with Components/External groups
for unowned nodes. Single-click selects; explicit drill controls navigate
only nodes marked drillable. Breadcrumb/up controls call the focus API.
Search checks name, namespace, and named operations but does not alter
scene topology.

Inspector shows existing class mean CRAP, coverage, executed mutation score,
method-level values, and incoming/outgoing relationships from actual
edges. Related class buttons select that class. Method buttons request
only real named operations. Render source as escaped text with numbered
lines and selected-line emphasis. Missing identity/source is explicit.

Open Project uses a labeled modal form for project root and diagram path;
this is not a browser directory picker or automatic scanner. Open/reload
success replaces the snapshot, clears obsolete selection/member state,
and closes the dialog; failure leaves it open with the error.
Use `role="alert"` for errors and `role="status"` for progress.

Implement the approved white/pale-canvas/blue-accent design with
system-font fallbacks, separators, compact controls, visible keyboard
focus, and a responsive layout. Keep all shown controls functional.

- [ ] **Step 5: Run `npm test && npm run build` from `web/`.**

Expected: passing component tests and a typechecked production bundle.
Start the real server and confirm `/` serves this built app.

- [ ] **Step 6: Commit `feat: add the web explorer and source inspector`.**

Include required trailers.

## Task 5: Real SVG architecture canvas and viewport interaction

**Files:** Create `Diagram.tsx`, `viewport.ts`, `Diagram.test.tsx`,
`viewport.test.ts`; modify `App.tsx` and `styles.css`.

**Consumes:** `Scene`, selected ID, metric mode, `onSelect`, `onDrill`.

**Produces:** `Diagram` and pure `fitViewport` / `zoomAt` functions.

- [ ] **Step 1: Add geometry and interaction tests.**

```ts
it("keeps the point under the pointer stable while zooming", () => {
  const start = {x: 30, y: 20, scale: 1};
  const result = zoomAt(start, {x: 100, y: 80}, 2);
  expect(result).toEqual({x: -40, y: -40, scale: 2});
});

it("renders precomputed routes rather than inventing topology", () => {
  render(<Diagram scene={library.scene} selectedId={null}
    metric="crap" onSelect={vi.fn()} onDrill={vi.fn()} />);
  expect(screen.getAllByTestId("class-node")).toHaveLength(
    library.scene.classes.length);
  expect(screen.getAllByTestId("edge")).toHaveLength(
    library.scene.edges.length);
});
```

Test min/max zoom clamps, fit including negative scene extents, zero-sized
viewport guards, click versus drag, keyboard selection, explicit violation
styling, and missing-score neutral styling.

- [ ] **Step 2: Run `npm test -- viewport.test.ts Diagram.test.tsx`.**

Expected initially: missing new canvas functions/components.

- [ ] **Step 3: Implement pure viewport math and SVG rendering.**

```ts
export interface Viewport { x: number; y: number; scale: number }
export function zoomAt(
  view: Viewport, pointer: {x: number; y: number}, factor: number
): Viewport {
  const scale = Math.min(3, Math.max(0.2, view.scale * factor));
  const ratio = scale / view.scale;
  return {
    x: pointer.x - (pointer.x - view.x) * ratio,
    y: pointer.y - (pointer.y - view.y) * ratio,
    scale,
  };
}
```

For fit, use scene min-x/min-y and total extent, viewport dimensions,
32px padding, and the same zoom bounds. Center the scene's actual bounds,
not only the upper-left class. On resize retain the current interaction
view; apply initial fit once per new document/focus, not on selection.

Render package rectangles/labels and section labels, then precomputed edge
strokes, then class cards and dependency ports. Use prepared `tip` and
`behind` to orient UML arrowheads. Dependency/implements are dashed,
inheritance/implements have hollow triangles, aggregation/composition
have origin diamonds, and association/dependency have appropriate tips.
Only `violating === true` gets red violation styling.
Place relationship labels near their actual routed geometry.

Class text follows existing `lines`, pad 12, line height 18, and rule gap
12 so the engine's rectangles and text remain compatible. Support oval
foreign nodes and provided port rectangles. Use backend bands for CRAP
and mutation; coverage uses a clearly labeled percentage scale with
neutral nulls. Do not label illustrative MockUI numbers as real data.

Use one SVG group transform for pan/zoom. Pointer capture handles drags;
a movement threshold of 4 pixels distinguishes a node click from a drag.
Wheel zoom anchors at local pointer coordinates. Fit and plus/minus
buttons are keyboard-accessible, and class nodes have labels/focus and
Enter selection. An explicit open/drill button or double click drills.

- [ ] **Step 4: Run all frontend tests and the production build.**

Run: `npm test && npm run build`.
Verify long names, multiple diagram sections, negative edge extents,
hierarchical ports, and selection with the actual checked-in documents.

- [ ] **Step 5: Commit `feat: render interactive UML scenes in SVG`.**

Include required trailers.

## Task 6: Production browser verification and usage documentation

**Files:** Create Playwright config/tests and small e2e project fixtures;
modify `README.md` and only directly coupled implementation files if
verification finds defects.

**Consumes:** Built app, CLI, and real endpoints from Tasks 1-5.

**Produces:** Repeatable browser checks and documented working launch.

- [ ] **Step 1: Add a real-server Playwright suite.**

Use the production build, not a Vite mock server. Configure Playwright to
start from `web/`:

```ts
webServer: {
  command: "npm run build && cd .. && clojure -M:web --port 4173",
  url: "http://127.0.0.1:4173",
  reuseExistingServer: false,
}
```

Install the selected Chromium runtime only if the browser test reports it
missing. Do not replace the user's browser profile.

Include this test:

```ts
test("explorer selection updates the real inspector", async ({page}) => {
  await page.goto("/");
  await expect(page.getByRole("heading", {name: "Explorer"})).toBeVisible();
  await page.getByRole("button", {name: "Catalog", exact: true}).click();
  await expect(page.getByTestId("inspector-title")).toHaveText("Catalog");
  await expect(page.getByTestId("class-node").filter({hasText: "Catalog"}))
    .toHaveAttribute("aria-selected", "true");
});
```

Additional cases open the source fixture and inspect class/method text,
search by a named method, navigate a hierarchical fixture up/down, change
metric mode, zoom/pan/fit, reload changed fixture metrics, and encounter
missing/malformed EDN. Assert there are no unexpected console errors.
Use separate temporary copies for tests that change files.

- [ ] **Step 2: Run production browser tests and fix actual failures.**

Run from `web/`: `npm run test:e2e`.
Then run the complete new backend suite plus existing directly dependent
document/overlay/layout tests:

```sh
clojure -M:web:spec spec/uml_viewer/web \
  spec/uml_viewer/main/web_viewer_spec.clj \
  spec/uml_viewer/application/document_spec.clj \
  spec/uml_viewer/application/overlay_spec.clj \
  spec/uml_viewer/engine
```

Run frontend unit/type/build checks once after the last code changes.

- [ ] **Step 3: Verify the approved visual contract in a real browser.**

Capture a 1536x1024 desktop screenshot. Compare three-pane proportions,
toolbar, selected-class emphasis, light surfaces, metric/source inspector,
and diagram legibility against the approved image. Also inspect a narrow
viewport for usable pane switching/stacking and keyboard navigation.
Save screenshots in the session artifacts, not the repository.

- [ ] **Step 4: Document actual commands and limitations.**

Add to README:

```sh
# From the uml-viewer repository:
npm --prefix web ci
npm --prefix web run build
clojure -M:web

# Choose another project with an existing generated EDN diagram:
clojure -M:web --root /path/to/project --diagram examples/project.edn
```

Explain the printed local URL, attached server shutdown with Ctrl+C,
explicit project/diagram dialog, existing IR generation prerequisite,
supported source identities, N/A metrics, and absence of agent/analysis
execution in this milestone. Existing Quil commands remain documented.

- [ ] **Step 5: Commit `test: verify and document the local web viewer`.**

Include required trailers. Check repository status and report any
incomplete feature plainly; do not claim a functional rebuild from
only a successful frontend build or a mock screenshot.

## Plan self-review

- Scope is one additive viewer interface; no analysis/agent subsystem is included.
- Tasks 1-2 cover flat/hierarchical data, identities, metrics, and source confinement.
- Task 3 covers the CLI, same-origin token transport, static confinement, size limits, and explicit errors.
- Task 4 covers real three-pane content, async correctness, project opening, accessibility, and honest missing-data states.
- Task 5 covers SVG/port geometry, relationship semantics, and viewport interactions.
- Task 6 covers real-browser behavior, visual comparison, regression checks, rollout documentation, and process cleanup.
- Clojure functions, JSON property names, revisions, and TypeScript interfaces are defined before their consumers.
