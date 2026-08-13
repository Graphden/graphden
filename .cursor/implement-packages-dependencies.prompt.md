# Implementation prompt — Packages & Dependencies redesign

You are implementing the Graphden **Packages & Dependencies** redesign. Two
Cursor rules are authoritative — read BOTH first and treat every principle as
binding:
- **`.cursor/rules/function-metadata-and-identity.mdc`** — the single rule for
  WHERE a function property lives (graph by default → DB column only for
  identity-dedup/org-RLS/VCS → NEVER a name/prefix). It also carries the
  name-prefix hardcode audit + remediation status. Governs Slice 0 below and the
  packages "interface" concept.
- **`.cursor/rules/packages-and-dependencies.mdc`** — the packages model
  (P1–P5 + §10 acceptance).
Also honor CLAUDE.md, docs/PHILOSOPHY.md, and docs/PACKAGE_DISTRIBUTION.md.

## Prime directives
- **Property placement follows the fn-metadata rule.** A per-function property
  lives in the GRAPH by default (inherit a marker / a binding / a type — the
  `secret` = inherit `:secret-leaf` precedent); a DB column ONLY for
  identity-dedup (`anonymous-hash`, `name=nil`), org-RLS (`org_id`), or VCS
  plumbing; and NEVER a name or name-prefix. Classify by id/structure. Detect
  the optional registry via `window.API` probe, never by a name string.
- **Minimal entities (P3).** The only justified new DB column is
  `:package-version.org_id` (§5, RLS). Everything else reuses the graph
  (inheritance/bindings/types), immutable package-versions, per-branch
  versioning, or localStorage (personal state). Any other new field/entity needs
  a written justification against the fn-metadata rule.
- **Safety (P2/P5).** No cross-org data leak. Add a two-org RLS test before
  claiming isolation. Publish is capability-gated. No request-outliving cache
  keyed without org/principal.
- **Ship in verified slices**, each independently green and (where user-visible)
  reversible. Per slice: `bb ci --skip gitleaks` green, `bb visual` green
  (update baselines ONLY for the intended UI), and a Playwright behavioral test
  of that slice's flow. Follow the repo's release-train (push develop → tenancy
  CI → `bb release <full-sha> --push` from graphden-cloud → verify prod). Never
  release red. Chat in Russian, code/comments/commits in English.

## Current state (fold in before starting)
- **Slice A1 is IMPLEMENTED and locally verified but NOT committed.** It relocated
  the packages panel off the Organization surface onto a Build-surface context-bar
  chip (`#gd-pkg-chip`) → popover (`#gd-pkg-pop`, 460px) that lazy-loads the
  existing `/partials/packages-panel`. Files touched: `app/editor/fns.edn`
  (ctxbar chip), `app/editor/editor-shell.js` (`gdOpenPkgPop`/`gdRevealPkgChip`),
  `app/editor/editor-sidebar.js` (removed the Organization `mountAdminSection`
  packages mount), `app/editor/editor-styles.css` (chip build-only + popover
  width). Playwright: chip visible on Build, popover opens + loads, hidden
  off-Build, gone from Organization — all PASS.
- **KNOWN GAP in A1 (must fix): it moved the COMBINED panel** (installed +
  browse/install + PUBLISH form) onto the Build chip. Per the spec, publish must
  NOT live on the Build chip — it is an authoring action on a namespace (§3), and
  governance lives on Organization (§4). Do not leave publish on the chip.
- **Name-prefix hardcode remediation — CORRECTED & PARTLY LANDED.** The DB has
  TWO anonymity classes (verified by query, see the fn-metadata rule's audit):
  composite-TYPE anons (`name=nil` + `anonymous_hash` set) and composed anon
  fn-defs (`_anon-<hash>` name, `anonymous_hash` NULL — the majority). The
  earlier "replace every `_anon-` check with a field check" pass was a
  REGRESSION for the composed class and was **reverted** (sync leftover-scan,
  integrity orphan-anons, types_api candidates, editor-fn-picker). What actually
  landed: (1) **committed** — `local-fn-name?` dead-code deletion (a `_`-prefix
  "local" classifier no one called), full-suite + focused-test + lint-clj green;
  (2) **staged, JS, needs Playwright** — `editor-secrets.js` secret classify by
  id not name. The `_anon-` name is IDENTITY (ADR name→id), so it legitimately
  stays; recognize an anon by `(or anonymous-hash _anon-name)`. Full
  field-unification of anon is DEFERRED (UNIQUE-constraint + version-plane risk).
- **CI runner flake (ci.clj:398 future-wave) recurs even on a quiet host** with
  "Stream closed" — it is the ORCHESTRATOR, not the checks. Validate slices by
  running the components directly (`bb lint-*` + `bb test-unit`, or focused
  kaocha) rather than trusting a single `bb ci` verdict.
- Already shipped this session and assumed coherent (do not regress): Workspaces
  (build-surface namespace scoping via `#gd-ws-chip` + localStorage + `⊘` hide),
  the lens (kind focus chips), branch chip, intent-based surfaces. The packages
  work must match these patterns (ctxbar chip + `gd-pop` popover; personal state
  in localStorage; classification by field/id).

## Build order (slices)

**Slice 0 — function-metadata foundation (do first).**
The hardcode remediation is DONE and corrected (see Current state): the dead
`local-fn-name?` classifier is committed; `editor-secrets.js` (secret by id) is
staged pending a Playwright check; the anon field-only rewrites were reverted as
regressions (the `_anon-` name is identity). Remaining Slice-0 work: verify
`editor-secrets.js` against the running Secrets panel, then design + implement
the graph-native **visibility / public-interface** property per the fn-metadata
rule (inherit a marker fn, the `secret-leaf` precedent; or an export construct)
— this is the representation Slice A1-finish's "interface" and §6 depend on, and
it retires the last cosmetic `_`-prefix use (`displayLabel`). Verify: a fn's
visibility is read from the graph (id/structure), never a name; a Playwright
check that the interface shows/hides by the graph marker.

**Slice A1-finish — Build-surface browser (no schema).**
Turn the relocated popover from "the old combined panel" into the spec's
*install/browse* browser only: search box; per-package detail (versions list,
public interface = the fns the graph marks public per Slice 0, requested effects
from the bundle); a **version selector** that installs/switches to ANY version (older =
rollback via the existing symmetric update); an **"update available"** badge when
a newer version than the pin exists. REMOVE the publish form from this popover.
Backend reuse: `GET /api/packages` (index), `GET /api/packages/:name/:version`
(full bundle, currently unused by UI). Verify: browse + search + install a chosen
(incl. older) version + update-available badge.

**Slice A2 — Publish as a namespace action (no schema yet).**
Add a "Publish" row-action on a namespace (the project/subtree), replacing the
buried publish form. It collects name/version and publishes the subtree. Ensure
publish **freezes transitive dependency versions** into the bundle (verify the
publish path records the resolved dep `(name,version)` set; if it resolves at
install instead, move the freeze to publish). Verify: publish a namespace →
appears in the index at the pinned dep set; reinstall reproduces exactly.

> **A2 LANDMINE (learned the hard way — a first attempt hung boot).** A first
> A2 cut added a second publish handler (`/api/packages/ns-publish`) whose
> response re-used the existing deep publish/secrets fn-def closure
> (`:_pkg-publish-result` + the `:_pkg-pub-secrets-notice-maybe` `:if`/union
> chain in `registry/registry/fns.edn`). At boot the compiled-executor
> whole-graph type-check (`graphden.types.check/effective-ref-return` →
> `signature-return` → `make-union` → `subtype?`) blew up: main thread pinned
> ~100% CPU for 150s+ (bounded by `seen`-set + `depth<6`, so not infinite —
> super-linear), the container never became healthy, `bb rebuild` failed at the
> 360s health timeout. Root cause: adding a SECOND entry point that unifies the
> same deep union-producing secrets closure squared the union work. **Do NOT
> re-ref the secrets/`:if` closure from the ns-publish response.** Return a
> SHALLOW notice built only from the posted `name`/`version` (plain `:get` from
> the form body — cheap), publish via a `:do [publish-result, shallow-notice]`,
> and skip the stripped-secrets re-read (or surface secrets a cheaper way).
> Rebuild and confirm the container reaches `healthy` BEFORE building UI on top.
> If a richer type-check is genuinely needed, that is a type-system perf task
> (memoize effective-ref-return across the boot check), NOT part of A2.
>
> A2 is currently REVERTED; A1 (install→Build chip) shipped without it. The
> tutorial (lesson 14) documents publish via the `curl`/API for now and notes
> the in-editor publish affordance is planned — keep that until A2 lands.

**Slice B — publish capability (tenancy). ✅ SHIPPED 2026-08-13.**
Done: `:publish-packages` added to `grant/org-management-capabilities`; open-core
org-cap SEAM (`tenancy/context.clj` `install-org-cap-fn!`/`current-has-org-cap?`);
guard at the publish chokepoint `registry/impls.clj publish-package-apply`
(single-tenant-safe via `current-platform-tier?` short-circuit); ⬆ button gated
in `editor-create.js`; tenancy bridge `org-admin/current-has-org-capability?`
(reads new `*admin-base*`, bound in `addon.clj`); test `org_admin_test.clj`
`current-has-org-capability-seam` (7 assertions). Commits: open-core `707c168b`
(develop), tenancy `8edfef0` (main). Verified single-tenant no-regression + test.
STILL TODO in this theme (deferred, optional): informed-consent at INSTALL time
(surface the package's requested effects + public interface before install) — not
yet built.

**Slice C — org-scoped private registry (schema + RLS).**
Add `:package-version.org_id` (nullable) + Postgres RLS mirroring `:fn`/`:ns`
(versioned-column change = schema + codec + versioned-mirror + version-data-fields
per the repo's enum/field checklist; rollback-tolerant migration). Publish →
publisher's org (private) by default; an explicit "public" opt-in sets a
platform-visible row (org_id NULL or a public flag — choose the shape that keeps
RLS simple). Browser shows [My org] + [Public]; another org's private packages
are invisible + uninstallable. Keep `:package-install` per-org/branch as-is.
Verify: a two-org integration test — org A publishes private; org B cannot see or
install it; org A publishes public; org B can. RLS FORCEd; operator sees only
public unless scoped.

**Slice — Governance view (Organization surface).**
A read-mostly Organization panel: the org's package catalog, who-may-publish
(the capability holders), and an install audit (what is installed where). Not an
install button.

## Coherence guardrails (from the review — hold these)
- Encapsulation (interface vs internals) is a GRAPH property (a visibility
  marker via inheritance, the `secret-leaf` precedent) — never a name/prefix and
  never a new DB column (per the fn-metadata rule + §6). Until enforcement is
  added it is a graph fact used for UI show/hide, not yet a security boundary —
  say so in code comments.
- "Change a bit" is inheritance; fork is the escape hatch (§7). Do not add a new
  "override" concept.
- The context-bar hosts project-context selectors (workspace, branch, packages);
  the explorer filter-bar hosts the view lens. Do not move one into the other.
- Registry-absent ⇒ all package UI hidden via `window.API` probe.
- Until Slice C lands, do NOT advertise cross-org safety — publish/browse are
  still global; Slice C is what makes the isolation claim true.

## Definition of done
All acceptance invariants in the spec §10 hold; A1–C + governance shipped to prod
via the release-train, each verified; no name/prefix hardcode; the single new
field is `:package-version.org_id`; a two-org isolation test is green.
