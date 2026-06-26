# E2E scenario coverage matrix

Per the project's coverage-measurement philosophy
([[feedback_coverage_measurement]]): e2e necessity is measured by
**user-scenario coverage**, NOT by cloverage line/form percentages.
This document inventories user-facing flows in the editor and notes
which have e2e tests + which don't.

Generated: 2026-06-23. Refreshed 2026-06-26 — the three "high-value
gaps" originally flagged below have all shipped
(`edit-provenance-popover.test.js`, `edit-row-actions-pin.test.js`,
`edit-prefs.test.js`); the gap section is kept for context but the
matrix above absorbs them.

## What's covered (49 files, grouped by concern)

### Auth & access

- ✓ Lock chip → password popover → login / sign-out (`edit-auth-login`)

### Prefs (theme + sidebar)

- ✓ Theme toggle + sidebar collapse/expand (`edit-prefs`)

### Sidebar & navigation

- ✓ Text-search filter (`edit-sidebar-filter`)
- ✓ Fn-picker open / filter / pick (`edit-fn-picker`, `edit-fn-picker-filter`)

### Fn lifecycle (create / edit / delete)

- ✓ Create fn under a namespace (`edit-fn-create`)
- ✓ Rename fn via pencil (`edit-fn-rename`)
- ✓ Edit description (`edit-description`)
- ✓ Re-parent flow + MI variations
  (`edit-phase3-reparent`, `edit-reparent-mi`,
  `edit-inheritance-regression`)
- ✓ Delete fn (via deleteFnByName cleanup in 40+ tests)

### Arg / binding flows

- ✓ Edit a bound arg's value (`edit-arg-value`)
- ✓ Arg-value validation (`edit-arg-value-validation`)
- ✓ Arg type flip + type-override
  (`edit-arg-type`, `edit-arg-type-override`)
- ✓ Edge-label rename (`edit-edge-rename`)
- ✓ Free-arg bind: literal / fn-ref / strip
  (`edit-free-arg-literal`, `edit-free-arg-fn-ref`,
  `edit-free-arg-strip`)
- ✓ Mismatch explainer (`edit-mismatch-explainer`)

### Sequence (list) flows

- ✓ Add / remove / first-item (`edit-phase5-sequence`)
- ✓ Sequence-items full coverage (`edit-sequence-items`)
- ✓ Regression: sequence with fn-ref (`regression-sequence-fn-ref`)
- ✓ Regression: value-binding migration on fn-ref
  (`regression-migrate-on-fn-ref`)

### Type-row CRUD

- ✓ Type creation: refinement + cancel (`edit-type-create`)
- ✓ Type creation: record + union (`edit-type-create-kinds`)
- ✓ Type editing: record / list / variant / record-remove
  (`edit-type-edit`, `edit-type-edit-list`, `edit-type-edit-variant`,
  `edit-type-edit-record-remove`)
- ✓ Type chip ▸/▾ expand (`edit-type-chip-expand`)

### Effects display

- ✓ Effect badges on fn-card (`edit-effects-badges`)
- ✓ Effect drift annotation (`edit-effects-drift`)
- ✓ Effect-chip click → explainer (`edit-effects-explainer`)

### Type provenance + row actions

- ✓ Provenance ↳ popover from arg-type chip (`edit-provenance-popover`)
- ✓ Row-actions ⋯ pin / outside-dismiss / Escape (`edit-row-actions-pin`)

### Execute (Run)

- ✓ Run popover smoke (`edit-execute` — Phase A-D inc. history Repeat)
- ✓ Advanced run flows (`edit-execute-advanced`)
- ✓ :process / :network effect confirm + drift
  (`edit-execute-effects`)

### Namespace lifecycle

- ✓ Rename + delete ns (`edit-namespace-edit`)
- ✓ Move fn to another ns (`edit-namespace-move`)

### Versioning (branches)

- ✓ Branch create / switch / delete (`edit-branch-lifecycle`)
- ✓ Branch diff modal navigation (`edit-branch-diff-navigate`)
- ✓ Branch-local annotation (`edit-branch-local`)
- ✓ Merge conflict modal (`edit-merge-conflict`)
- ✓ Fn-versions ⌛ popover (`edit-fn-versions`)
- ✓ Fn-versions Restore action (`edit-fn-versions-restore`)

### Services

- ✓ ⚙ button + reject path (`edit-service`)
- ✓ Full lifecycle: create / toggle / sibling-warn / delete
  (`edit-service-lifecycle`)

### Secrets

- ✓ Sidebar secrets panel (`edit-secrets-panel`)
- ✓ Secret list (`edit-secrets-list`)
- ✓ Secret rotation (`edit-secrets-rotate`)

---

## What's NOT covered (real gaps)

These are user-visible flows with NO dedicated e2e test. Most are
UI-only (no state mutation), so a regression surfaces as a visual
quirk — not data loss — but a smoke test would still catch the
"button doesn't render / popover doesn't appear" class of bug.

### High-value gaps

_All previously-flagged high-value gaps (provenance popover, row-actions
pin/unpin, prefs theme + sidebar) have shipped — see the matrix above._

### Medium-value gaps (UI-only, less critical)

| Flow | Module | Notes |
|---|---|---|
| Hover tooltip (description + full-name popovers) | `editor-tooltips` | Hover events are notoriously flake-prone in e2e — would need slow polling |
| ↗ open-in-new-tab fn-card link | `editor-icons` | Trivial; failure obvious to first user |
| Drag-and-reposition overlay | `editor-drag` | Cosmetic; cytoscape handles the heavy lifting |
| Type-chip ▸/▾ inline expansion panel | `editor-overlay-type-expand` | Provenance popover tests would surface most regressions |
| Tier-2 custom value-form widget (rating slider) | `editor-widget-rating` | Sample/reference code; if broken, only sample fails |
| Bottom-of-card metadata strips (effects/parents/ns/HOF) | `editor-overlay-strips` | Read-only display; covered indirectly via overlay manager smoke |

### Infrastructure (no dedicated e2e expected)

These editor modules are foundation layer, exercised through every
test that uses them. A failure here would surface as 40+ test fails,
which is the e2e signal we want:

`editor-busy`, `editor-cytoscape`, `editor-drag`,
`editor-overlay-manager`, `editor-overlay-arg`, `editor-overlay-edge-label`,
`editor-popover-base`, `editor-state`, `editor-data`, `editor-layout`,
`editor-edit-validation`, `editor-edit-modes`, `editor-edit-reparent`,
`editor-create`, `editor-create-type`, `editor-value-form`,
`editor-namespace-picker`, `editor-execute-result`, `editor-execute-history`,
`editor-fn-picker` (picker UI is `editor-fn-picker.test.js`, helpers in
`editor-data` are exercised everywhere), `editor-service-popover`
(covered by `edit-service.test.js`), `editor-secrets` (covered by
`edit-secrets-*.test.js`).

---

## What user-flow audit recommends

All three user-scenario gaps flagged in the 2026-06-23 audit have
shipped (`edit-provenance-popover` / `edit-row-actions-pin` /
`edit-prefs`). The medium-value gaps (tooltips, drag, etc.)
deliberately stay uncovered — their risk doesn't justify the e2e
maintenance overhead.
