---
name: uml-viewer
description: Use when drawing or viewing a project's architecture as a UML class diagram, writing a .policy.edn or generating uml-viewer IR (Clojure, TypeScript/JavaScript, Rust, Python, C#, or PowerShell), checking dependency direction or Clean Architecture layering, installing get-uml-viewer or ./uml in a project, or working with the uml-viewer Copilot CLI companion and its .uml-viewer mailbox.
---

# uml-viewer

## Overview

uml-viewer draws a project's real namespace or module tree as UML, colors
each class by CRAP and mutation score from `.metrics/`, and flags
dependencies that break the Clean Architecture dependency rule: source
dependencies point inward. It scans Clojure, TypeScript, Rust, and Python.
The diagram is generated: write the policy, never edit the IR.

`UV` is the uml-viewer checkout, three directories above this skill's base
directory (`<base>/../../..`). `<dir>` is the examined project's directory
name. Run each step from the examined project's root unless it says
otherwise.

## Setup (in order)

1. **Metrics first (REQUIRED).** Without them every class shows red or N/A.
   - Plain `crapper` with no path (REQUIRED SUB-SKILL: crapper).
   - `mutator` on the source files (REQUIRED SUB-SKILL: mutator). Exit 3 is
     a result. On a large project, start with the files the user cares about
     and say which classes stay red.
2. **Policy.** Write `examples/<dir>.policy.edn` with
   `:out "examples/<dir>.edn"`, the file `./uml --restart` reopens.

   ```clojure
   {:title "Shop" :lang :python :src "src" :prefix "shop"
    :out "examples/shop.edn" :hierarchical true
    :order [web service domain]          ; real top-level segments
    :levels [[domain] [service] [web]]}  ; inner (higher-level) first
   ```

   - The tree is the namespaces. Never invent `:packages` or layer names;
     `:order` and `:levels` name real segments only.
   - Several languages: `:sources [{:lang :typescript :root "src"} …]`
     replaces `:src` and `:lang`.
   - `:foreign` lists libraries to draw as ovals. Add `:proposals` only when
     the user asks to view a grouping that is not in the code.
   - Without `:levels`, nothing is marked violating.
3. **IR.** Rerun after every source or policy change. Without `:out` the
   generator writes `examples/uml-viewer.edn`.

   ```bash
   clojure -Sdeps "{:deps {uml-viewer/uml-viewer {:local/root \"$UV\"}}}" \
     -M -m uml-viewer.main.ir-generator examples/<dir>.policy.edn
   ```

4. **Launcher.** Install without opening a window:

   ```bash
   UML_VIEWER_REPO_URL="file://$UV" "$UV/scripts/get-uml-viewer" --install-only
   ```

   It clones the committed state of `UV` into `.uml-viewer/uml-viewer/`,
   gitignores `.uml-viewer/` and `uml-viewer-log.txt`, and writes `./uml`.
   Without `UML_VIEWER_REPO_URL` it clones upstream `master`, which may
   differ from this checkout.
5. **View.** Tell the user to run `./uml` and what it starts (see
   Companion); start it yourself only when asked. For a read-only browser
   view with no companion, run from `UV`: `npm --prefix web ci`,
   `npm --prefix web run build`, then
   `clojure -M:web --root <project> --diagram examples/<dir>.edn`.

## Report layering

`grep -n ':violating true' examples/<dir>.edn` lists each edge where an inner
component depends on an outer one. Report each as from → to. The usual fix
is Dependency Inversion: the inner side owns an interface and the outer side
implements it. Change code only when asked.

## Companion

`./uml` opens the diagram window and starts one interactive Copilot CLI
session for the project in tmux, shown in a Terminal window. It runs
`copilot --yolo`, so every tool call is approved automatically: tell the
user before they start it. Its standing rules are in
`.uml-viewer/.github/instructions/`, and it refreshes metrics and IR after
changes. Closing the window ends only that session.

The viewer and the companion talk through queues in `.uml-viewer/`:
`to-agent.edn` (viewer to companion) and `to-viewer.edn` (companion to
viewer). Pop each command as you handle it. Only the companion runs
`./uml --restart`.

## `.metrics/`

Never delete it. Commit the refreshed snapshots if `git ls-files .metrics`
lists files; otherwise leave them untracked and tell the user they exist.

## More

- README: [Run](../../../README.md#run), [Policy](../../../README.md#policy),
  [Dependency rule](../../../README.md#dependency-rule),
  [Companion mailbox](../../../README.md#companion-mailbox)
- [The Clean Architecture](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html)
- [GitHub Copilot CLI](https://github.com/github/copilot-cli)
