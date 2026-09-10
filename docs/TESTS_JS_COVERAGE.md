# Frontend coverage — the e2e suite's reach into the editor, 2026-09-11

A SNAPSHOT, not a gate. Line/form percentages say nothing about whether
an e2e test is needed — e2e covers human-observable flows, and the tool
for judging it is a user-scenario inventory (docs/TESTS.md § Coverage,
`feedback_coverage_measurement` rule 3). What this table IS good for:
finding editor modules the suite never executes at all, so a change
there lands with no automated signal.

## How it was taken

```bash
bb wt up                                   # an isolated stack of YOUR tree
cd tools/browser-test
GRAPHDEN_JS_COVERAGE=/tmp/jscov GRAPHDEN_URL=http://localhost:<port> \
  AUTH_TOKEN=test123 ./run-edit-tests.sh   # ~90 min, 92 specs
node js-coverage-report.js /tmp/jscov      # this table
```

Every spec's browser dumps V8 block coverage on close; the bundle source
is written once per directory and each spec adds only its ranges (~75 MB
for the full suite). The report locates each editor module inside the
bundle, flattens the ranges innermost-wins, and counts a LINE as covered
when any non-blank byte on it ran in any spec. Comment and blank lines
are excluded from the denominator, so the number reads like cloverage's
line %.

## The number

| Suite | Specs | Modules | Lines | Covered | % |
|---|---:|---:|---:|---:|---:|
| e2e (`run-edit-tests.sh`) | 91 | 97 | 23536 | 17965 | 76.3 |

Three quarters of the editor's shipped JavaScript runs during the suite.
The gaps below are where a regression would land silently.

## Modules under 60 %

| Module | Lines | Covered | % | Why (read before trusting the number) |
|---|---:|---:|---:|---|
| app/editor/editor-expansion.js | 115 | 4 | 3.5 | Ancestor expansion levels — driven from the URL spec (`root:1`), which only `check-editor.js` uses, never a spec. |
| app/editor/editor-trace-view.js | 107 | 4 | 3.7 | The execute trace pane; `edit-execute-trace` asserts the API, not the rendered tree. |
| app/editor/editor-account.js | 251 | 15 | 6.0 | The account page — only the login flow is walked (`edit-auth-login`). |
| web/runtime/graphden-actions-builtin.js | 61 | 4 | 6.6 | Built-in actions for USER-composed pages, not the editor; nothing in the suite renders such a page. |
| app/editor/editor-edit-validation.js | 81 | 7 | 8.6 | Client-side value validation; the specs assert the SERVER guard instead. |
| app/editor/editor-widget-rating.js | 31 | 3 | 9.7 | The marketplace star widget — `edit-marketplace` posts a review through the API. |
| app/editor/editor-apps.js | 126 | 26 | 20.6 | The Apps panel per-fn ▣ surface. |
| app/editor/editor-org-switcher.js | 232 | 48 | 20.7 | Org chip + New organization — tenancy-only; the self-host suite cannot reach it (org tours run on the cloud stack). |
| app/editor/editor-overlay-type-expand.js | 438 | 98 | 22.4 | Type-expansion overlay interactions beyond the first level. |
| app/editor/editor-tour-picker.js | 159 | 42 | 26.4 | The tour picker list; the tour specs start a lesson by id. |
| app/editor/editor-drag.js | 60 | 19 | 31.7 | Node dragging — pointer gestures no spec performs. |
| app/editor/editor-type-expand-render.js | 382 | 122 | 31.9 | Deep record/variant rendering inside the expansion overlay. |
| app/editor/editor-edit-reparent.js | 225 | 90 | 40.0 | Reparent beyond the two shapes `edit-phase3-reparent` / `edit-reparent-mi` walk. |
| web/runtime/graphden-edn.js | 167 | 78 | 46.7 | The standalone runtime EDN reader — exercised by `tools/runtime-test`, not the browser suite. |
| app/editor/editor-value-form.js | 241 | 124 | 51.5 | — |
| app/editor/editor-viewport.js | 163 | 88 | 54.0 | — |
| app/editor/editor-auth.js | 603 | 330 | 54.7 | — |
| app/editor/editor-literal-types.js | 413 | 232 | 56.2 | — |
| app/editor/editor-feedback.js | 254 | 144 | 56.7 | — |
| app/editor/editor-edit-modes.js | 567 | 323 | 57.0 | — |
| app/editor/editor-path-view.js | 235 | 134 | 57.0 | — |

## What to do with this

- A module at **0–10 %** with real logic is the honest finding: either a
  flow nobody walks (add a spec when the flow matters) or code only
  reachable on a stack the suite cannot boot (tenancy / user pages),
  which the table should say out loud.
- A module at **100 %** is not "fully tested" — block coverage counts
  execution, not assertions. Several of the 100 % rows are 10–16-line
  panel shims that any editor load executes.
- Re-take the snapshot when the suite or the editor changes shape
  enough that the gaps would move; it is a periodic audit, like
  `bb coverage-full`, not a per-change signal.
