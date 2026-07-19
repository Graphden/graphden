# Lesson 08 — Branches: fork, edit, diff, merge

**Goal**: by the end of this lesson you can fork the graph onto
a feature branch, edit fn-defs without affecting `main`, see what
changed, merge back — and understand why some fn-defs are
*branch-local* (their edits stay on the branch by design).

**Concepts introduced**: `branch`, `main`, `feature branch`,
`fork`, `diff`, `merge`, `:branch-local?`, `:skipped` audit list.

## Branches are views, not copies

The fn-graph stored in graphden's database has TWO faces:

1. **Identity rows** — `:fn` entities. One row per fn. Shared
   across every branch.
2. **Version rows** — `:fn-version`, `:binding-version`,
   `:fn-slot-version`, `:binding-list-item-version`. One row per
   (entity-id, branch-id) where someone made an edit.

A branch is just a name + a `:base-branch-id` pointer to its
parent. When you ask "what's the value of `:my-fn` on branch
`feat`?", the resolver walks: own version on `feat` → versions
merged into `feat` → recurse to the parent branch. That walk is
the only thing that "differentiates" branches. There's no
copying.

`main` is the root branch. Every other branch is a `fork` of
some existing branch — its `:base-branch-id` is the branch it
was forked from.

## The fork → edit → diff → merge loop

In the editor's top bar there's a branch chip showing your
current branch. Click it for the branch popover.

```
+ new           → fork from the current branch
list / switch   → pick a different one
Δ (diff)        → show the diff vs another branch
⇢ (merge)       → fold another branch into this one
× delete
```

### Try it

1. Click the branch chip. Click `+ new`, name it `feat-tutorial`.
   The editor reloads on `feat-tutorial`. URL gets `?branch=feat-
   tutorial`.
2. Navigate to any fn-def with a literal value (e.g. one of the
   tutorial fns you made in lesson 01). Edit the value.
3. Switch back to `main`. The fn-def is unchanged — your edit
   lives only on `feat-tutorial`.
4. Open the branch popover, click `Δ` next to `feat-tutorial`.
   The diff modal lists every entity that resolves differently.
5. From `main`, click `⇢` next to `feat-tutorial`. Confirm.
   The page reloads and `main` now sees your edit.

## Conflicts

If you edited the same entity on BOTH branches after their fork
point, merge throws. The conflict modal asks "which side wins"
per entity. Pick `source` (the branch you're merging in) or
`target` (the branch you're merging into) per row, hit `Apply`.

## What DOESN'T merge: branch-local fn-defs

Some fn-defs encode environment-specific runtime config — a
web-server's port, a Vault secret's path, a cron schedule. You
don't want those merging from `dev` into `main` and silently
clobbering production.

Graphden marks these with `:branch-local? true` on the `:fn`
row. The flag is **monotonic-OR over `:parent-ids`**: if any
ancestor is sticky-local, you are too. Seeded defaults:

| Seeded sticky-local | Why |
|---|---|
| `:http-server` | Port + handler are per-environment |
| `:secret-leaf` | Vault path is per-environment |
| `:schedule` | Cron cadence is per-environment |
| `:env` | Env-var indirection is per-deployment |

Any fn-def parented from one of those inherits the flag. So this
fn-def…

```edn
{:name :my-web-server
 :parent :http-server
 :args {:handler :my-handler :port 8080}}
```

…is effectively branch-local because `:http-server` is. On a
merge, the resolver filters out its version rows on the target
branch; the editor's diff modal marks the row with `📍 branch-
local`, and the post-merge alert names exactly what didn't
propagate:

```
2 branch-local fns did NOT propagate to main:
:my-web-server, :my-vault-secret
(Marked with 📍 in the branch-diff modal.)
```

The merge API surfaces the same list as `:skipped {:branch-local
[…]}`:

```json
{
  "ok": true,
  "merge": { "id": "...", "source-branch-id": "...", ... },
  "skipped": {
    "branch-local": [
      {"entity-name": "fn", "entity-id": "uuid", "fn-name": "my-web-server"}
    ]
  }
}
```

The handler/business-logic fn-def REFERENCED by the branch-local
config (e.g. `:my-handler` above) DOES merge normally — only the
sticky-local node itself stays scoped. So you can iterate on
shared business logic on a feature branch and merge it cleanly
while leaving the per-environment config alone.

### Why this isn't just "version everything"

Asymmetry is intentional. **Merge** says "fold sibling's history
in". **Inheritance** (a branch reading from its `:base-branch-
id`) says "I'm a child branch, give me my parent's state".
`:branch-local?` blocks the first but not the second — when you
fork `dev` from `main`, `dev` correctly inherits `main`'s
sticky-local web-server config, because you EXPLICITLY chose to
fork.

## Try it (sticky-local edition)

1. On `main`, find `:web-server` (the editor's own server). Note
   its port (8080).
2. Fork to `feat-dev-server`. On the new branch, copy `:web-
   server` to a new fn-def parented from `:http-server`, port
   9001.
3. Open the branch-diff modal between `feat-dev-server` and
   `main`. Your new fn-def shows up with a `📍 branch-local`
   badge.
4. Merge `feat-dev-server` → `main`. The alert names your fn as
   skipped. Check `main` — it's not there.
5. Switch back to `feat-dev-server` — still there. The branch
   that produced it keeps it.

## What we glossed over

- Per-branch services (running `:my-web-server` as a managed
  service on dev while `:web-server` runs on main) — Lesson 10.
- How the resolver handles deep branch chains (B forked from A,
  C forked from B, merge edges everywhere) — see
  [docs/VERSIONING.md](../VERSIONING.md).
- The conflict-resolution shape returned by the API — see
  `:_merge-apply-err-conflict` in the branches package.

## Next

[Lesson 09 — Executing a fn](09-executing-a-fn.md)
