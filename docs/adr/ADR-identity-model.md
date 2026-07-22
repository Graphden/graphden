# ADR: fn identity — ids are identity, names are per-namespace labels

**Status:** ACCEPTED (2026-07-22) · direction set by the project owner
**Companion audit:** [AUDIT-name-vs-id-resolution.md](AUDIT-name-vs-id-resolution.md)

## Decision

1. **A fn's identity is its `fn-id` (uuid).** Every internal mechanism —
   registries, dispatch, caching, redaction — must key on the id.
2. **Names are human labels, unique PER NAMESPACE, not globally.**
   The per-branch `(namespace-id, name)` CRUD gate
   (`check-fn-name-collision!`) expresses the intended semantics; the
   package layer's global-uniqueness validation
   (`validate-no-name-collisions!`) is a *transitional* constraint that
   shrinks as name-keyed machinery is retired (see § Migration).
3. Name→id resolution happens at **boundaries only**: fns.edn authoring
   (the records parser), public APIs that accept names, UI search.

## The two authoring worlds (why this ADR exists)

The codebase grew two different identity models without a written
reconciliation:

| World | id minted as | rename semantics |
|---|---|---|
| **Package sync** (fns.edn) | `uuid-v5(namespace, name)` — deterministic (`packages/records/ids.clj`) | rename = NEW identity; the old row orphans. Names here are effectively immutable ids. |
| **Editor CRUD** | `random-uuid` (`crud/entities.clj` create path) | rename UPDATES `:name` at the same id — names are mutable labels. |

Both are correct for their world: deterministic ids buy the package
layer idempotent re-sync (`re-sync unchanged source is a no-op`), while
random ids buy user graphs stable identity across relabeling. The
failure mode was everything BETWEEN the worlds: in-memory registries
keyed by **bare name** silently conflated same-named fns the moment the
editor world (per-ns uniqueness) produced a duplicate the sync world
(global uniqueness) could not — empirically confirmed: the CRUD gate
accepts `dup-target` in two namespaces, and the name-keyed rich-types
registry then held ONE entry for the two fns (type checking, effect
classification and the secrets redaction all read whichever wrote last).

## Consequences

- **Rich-types registry** is id-keyed with a name index
  (`{:by-id {fn-id → entry} :by-name {name → fn-id}}`); the id-holding
  paths (executor compile, `/api/execute` persist/redaction, crud
  guards) read `rich-type-of-id`; the type-checker and public
  boundaries keep the name-keyed `rich-type-of` via the index.
- **Sync-path writes derive** the same `uuid-v5(ns, name)` their
  storage rows use, so a name-only write (the 220 existing test sites,
  the loader, the seed passes) needs no change; **crud-path writes
  thread the ROW id** (`reconstruct-fn-def` carries `:fn-id`).
- **Package rename stays identity-breaking** — accepted, documented
  here. Package source is regenerable/git-tracked; orphaning-on-rename
  is tolerable there and is the price of idempotent declarative sync.
  If package-rename-preserving-identity is ever needed, it requires
  breaking `fn-id = uuid-v5(name)` and re-founding sync idempotency on
  a rename ledger — out of scope.

## Migration ladder (per-ns names)

Stage 1 (this branch): registry re-key (above). Remaining, in order:

- **Stage 2 — type-alias registry: DONE (owner diagnostics).** Alias
  entries now track their declaring type-row's id (`alias-owners`
  side-table in `types/core.clj`); a cross-owner re-bind warn-logs
  loudly instead of silently shadowing. Resolution itself stays
  last-write-wins until stage 4 makes it namespace-aware — that is
  the honest scope: per-ns duplicates are LEGAL, so a hard reject
  would be wrong; invisibility was the bug.
- **Stage 3 — anon-hash use-site tuple + namespace: DONE.**
  `anon-fn-name` now mixes the host fn-def's namespace into the
  use-site identity (`[namespace parent-name arg-name]`), so
  same-named parents in different namespaces keep distinct anon
  entries. Synthetic anon names (and their derived ids) changed once
  as a consequence — old anon rows in long-lived dev DBs become
  unreferenced (harmless; clean deploys unaffected).
- **Stage 4 — qualified refs in fns.edn.** `:ns.path/name` keyword
  refs; an unqualified ref must be UNAMBIGUOUS across the loaded set,
  else sync fails with a suggestion to qualify.
- **Stage 5 — relax `validate-no-name-collisions!`** to per-(ns, name)
  — last, after stages 2-4 hold; base-fn (Clojure impl registry) names
  stay globally unique (they are code-level identifiers, like Clojure
  vars).
- **Stage 6 — UI disambiguation.** Search/deep-links show the
  namespace when a bare name is ambiguous (matches the "hide
  namespaces until needed" editor philosophy).
