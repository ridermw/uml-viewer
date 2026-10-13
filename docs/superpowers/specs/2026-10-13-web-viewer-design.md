# Local web viewer design

## Approved direction

Build a local browser interface matching the approved three-pane MockUI
concept: project explorer, architecture canvas, and metrics/source inspector.
Keep the existing Clojure analysis and layout core and the existing Quil
application. Basic exploration must not require Grok or another agent.

The first milestone is a working viewer for real EDN diagrams and existing
metric snapshots, not a replacement for every analysis or agent workflow.

## Scope

The first release supports:

- Opening a project directory and an EDN diagram within that directory.
- Loading both normalized flat documents and existing hierarchical documents.
- Exploring packages/classes and drilling into hierarchical components.
- Searching the explorer by class, namespace, or method name.
- Selecting a class from either the explorer or the canvas.
- Inspecting class and method metrics and incoming/outgoing relationships.
- Inspecting source for supported languages when the document identifies it.
- Coloring classes by CRAP risk, coverage, or mutation score.
- Panning, zooming, fitting the diagram, and manually reloading data.
- Showing explicit loading, empty, missing-data, and error states.

No project data is uploaded to a remote service.

### Non-goals

Do not add agent integration, policy editing, mutation execution, coverage
execution, automatic project scanning, or desktop application packaging.
Do not introduce nonfunctional Analyze, Proposals, or Connect agent buttons.
Do not change the existing `:run`, `:uml-viewer`, or restart behavior.

Users without a generated diagram receive a clear explanation that this
milestone loads EDN; the existing IR generator remains the way to create it.

## Architecture

### Clojure service

Add a separate `:web` CLI alias and `uml-viewer.main.web-viewer` entry point.
Serve the built frontend and JSON API from one loopback-only HTTP server.
Use the JDK HTTP server and a JSON serialization library; do not import
Quil or initialize a graphics window in this entry point.

Place HTTP transport code under `uml-viewer.web.server` and document/DTO
translation under `uml-viewer.web.model`. Keep transport, file access,
and browser-specific response formatting out of the existing domain core.

Reuse:

- `domain.ir/load-document` for EDN parsing and normalization.
- `application.overlay` for metric loading and namespace matching.
- `application.document/compile-view` for hierarchy selection and layout.
- `application.detail/model` for class identities and relationships.
- `source/member-source` and registered language extractors for source.

Load files directly for the web service rather than using
`document/waiting-state`; an available diagram appears immediately.
Avoid the existing UI-oriented `load-path` error-to-blank-state wrapper
where the API needs a structured error.

### Browser application

Create `web/` with React, TypeScript, Vite, and checked-in dependency
lockfile. Use ordinary CSS and SVG rather than adding a diagram framework:
the Clojure core already supplies class/package rectangles and routed
edge geometry.

Use typed DTOs and a small API client. Organize components around the
explorer, SVG canvas, inspector, and project-open dialog. Keep interaction
state in the browser; the server owns the current project/diagram and
authoritative compiled data.

The production server serves the locally built frontend. Runtime operation
does not depend on a CDN, remote font, telemetry service, or Vite dev server.

## Data flow and interfaces

The command accepts `--root`, `--diagram`, and `--port`.
Default root is the current directory; the initial example diagram is
`examples/library.edn`. Relative diagram paths resolve inside the root.
An omitted port lets the operating system choose an available port.
The command prints the browser URL and remains attached to the terminal.

Provide these logical API operations:

- Get the current document, available explorer hierarchy, compiled scene,
  metric availability, and current focus.
- Open an explicitly supplied project root and diagram.
- Compile a different hierarchy focus.
- Reload the selected diagram and its metric snapshots.
- Get source for a class or named member in the loaded scene.

An API request cannot supply an arbitrary source filename; it supplies
the loaded class identity and optional member name. The service resolves
the source through the existing language extraction interface.

Serialization must preserve complete keyword identities, including
namespaces and multi-diagram qualification. Geometry must remain numeric,
missing values must remain null, and class/edge IDs must agree between
the explorer, scene, inspector, and source requests.

Treat responses as immutable snapshots. Opening or navigating successfully
replaces the document snapshot; failed requests leave the last successful
diagram visible with an explicit error. Clear stale selections when their
class no longer exists, and discard stale asynchronous source responses.

## Metrics

Use the core's overlaid class and method values, not fixture-derived numbers.
CRAP class summaries use the existing aggregate rather than an invented
sum or maximum. Mutation score is killed / (killed + survived), excluding
uncovered sites; a zero denominator is N/A.

Missing coverage, CRAP, mutation snapshots, or member values display N/A
with neutral coloring. A known count of zero is different from missing data.
Coverage is formatted from the existing 0-1 representation.
Follow the repository's risk thresholds and preserve its violation flags.
Do not infer a dependency violation merely from an arrow pointing upward.

The status bar shows the actual loaded class/edge counts and reload time.
It must not claim that source changes were saved.

## Visual and interaction contract

Use the approved light desktop design: white surfaces, pale dotted canvas,
blue selection/accent, thin separators, restrained rounding, and compact
developer-tool typography. Do not add a chat sidebar.

The explorer groups real components/packages with class children.
Hierarchy navigation has breadcrumbs and an accessible back/up action.
Search narrows explorer results without silently dropping canvas topology.

Render class names and supported members from the real scene, with selected
class emphasis and small metric badges. Preserve edge kinds, direction,
labels, and violation styling. Reuse the prepared route strokes when
available rather than recomputing diagram layout in JavaScript.

The inspector shows the selected class, namespace, metric values, methods,
relationships, and a source tab. Selecting a related class selects it in
the canvas. Selecting a method can inspect its source location.

Fit computes the view from the scene bounds. Zoom is bounded and centered
on the pointer or viewport center. Pointer gestures must distinguish a
click from a drag. Resize preserves a usable viewport.

Use keyboard-accessible buttons, visible focus styles, semantic labels,
and status/error announcements. Adapt the pane layout to narrower screens;
the desktop three-pane layout remains the primary target.

## Local access and errors

Bind only to loopback. Reject unexpected Host/Origin values and do not
enable permissive CORS. Require a per-startup token on API operations.
The frontend obtains it through the same-origin application bootstrap;
it is never persisted as a credential.

Opening a project is an explicit operation. Canonicalize the chosen root,
diagram, static asset paths, and source paths. Diagram/source reads must
remain within the chosen project, including after symlink resolution.
Static file serving is confined to the built frontend directory.

Parse EDN as data, never evaluate it. Render names and source as text,
never executable HTML. Do not run shell commands from API input.
Apply finite request/source size limits and return clear oversized errors.

Return structured errors for invalid input, missing files, malformed EDN,
unrecognized class/member identities, unsupported source extraction,
outside-project paths, and unexpected server failures. Log unexpected
failures with the repository's logging mechanism. Do not return an empty
successful diagram in place of an error.

## Verification

Add focused Speclj coverage for DTO identities, diagram loading, hierarchy
navigation, metric/null semantics, source resolution, and HTTP behavior.
Cover canonical path confinement, cross-origin/Host rejection, and errors.
Existing layout and overlay tests must continue to pass.

Add frontend tests for metric formatting, selection/search, source loading
and stale responses, viewport math, and visible error states. Run
TypeScript checks and the production build.

Exercise the production server in a real browser using the checked-in
library diagram and a hierarchical document. Verify explorer selection,
search, class/method inspection, metric mode changes, navigation,
pan/zoom/fit, opening/reloading, and missing/malformed inputs. Compare a
desktop screenshot with the approved MockUI composition.

## Documentation and rollout

Document frontend installation/build, the `:web` command, selecting a
project/diagram, supported source lookup, and first-milestone limitations.
Ignore frontend dependencies, generated assets, and local test artifacts.
Keep the old viewer available throughout; this is an additive interface,
not an unannounced launcher replacement.
