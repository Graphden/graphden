# Lesson 20 — Branches: fork, edit, diff, merge

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

At the top of the Explorer there's a branch chip showing your
current branch. Click it for the branch popover — a flat list of
the org's branches (`main` and your current branch sort first).

```text
Create              → type a name, click Create
                      (forks from the current branch)
Advanced            → (cloud/tenancy only) "Who can write" the new
                      branch: "Everyone with write access (default)",
                      "Only me (org admins can unlock)", "Org admins only"
row → switch        → click a branch row to switch to it
Δ (compare)         → COMPARE MODE: pick this branch as the
                      second one and the whole editor becomes
                      the diff. The picked row's Δ stays lit;
                      click it again to exit.
✅ (approve)        → approve a proposed branch for merge
⇢ (merge)           → fold another branch into this one
⋯ (more)            → the labeled per-row menu:
                        💬 Review & comments — the conversation
                          dialog: what changed, the thread,
                          suggestions
                        📤 Propose for review — submit into its base
                        ⚙ Protection… — "push only via merge",
                          required approvals (0–3, one-tap segments),
                          count-own-approval. Works everywhere,
                          including single-user.
                        ⛨ Who can write… — (cloud/tenancy only) the same
                          three choices, on an existing branch
                        📦 Archive / Reopen — fold a finished branch
                          into the Merged group, or bring one back
                        × Delete branch
Merged · N          → collapsed group at the bottom: branches already
                      merged into their base. A merge folds its source
                      here (it cannot be deleted — the base reads
                      through it); click a row to reopen it (asks first).
                      The ⋯ is accented when the row is proposed or
                      protected; a 🔒 marks write-policy rows.
```

A *protected* branch refuses edits — and merges into it — from
anyone outside its policy (the branch owner and the org's admins
always keep access, so nothing can be locked forever). On a
self-hosted single-user instance there are no other users to keep
out, so the write-policy affordances (⛨ / Advanced) stay hidden.

### Push only via merge (⋯ → ⚙ Protection)

Open `⋯` → **⚙ Protection…** on a branch row and tick **"Push only via merge (no
direct writes)"**. Unlike write-access (⛨), this shows on a single-user
instance too — it doesn't care *who* you are, only *how* the branch
changes. With it on, the branch stops accepting direct edits: creating,
editing or deleting a fn-def straight on it comes back as *"This branch
accepts changes only via merge…"* (a 409). The only way to change it is
to **merge another branch into it** — exactly the GitHub "protect
`main`, land through pull requests" workflow.

The usual shape: turn it on for `main`, do your work on a child branch
(`feat-…`), then merge the child into `main`. It rides on the branch
itself, so it survives reloads and applies to every client. Untick it
any time to re-open direct writes.

### Change proposals & review

Requiring *review* before a merge — 📤 propose, ✅ approve, required
approvals on the target, comment threads and suggestions — is
[lesson 21](21-review.md)'s subject; its knobs live in the same
**⚙ Protection…** dialog.

### Try it — the plain flow

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=20)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

1. Make something to change: find `str-upper` (core.strings), `⋯` →
   **Extend**, name it `branch-demo`. Click the `+` on its `:string`
   slot, **Bind literal**, type `main version`, **Save**.
2. Click the branch chip. In the create row, type `tutorial-branch`
   and click **Create**. The editor reloads on `tutorial-branch`;
   the URL gets `?branch=tutorial-branch`.
3. Still looking at `branch-demo`, click its value and change it to
   `branch version`.
4. Switch back to `main` (branch chip → the `main` row). `branch-demo`
   reads `main version` again — your edit lives only on
   `tutorial-branch`.
5. Open the branch popover, click `Δ` next to `tutorial-branch` —
   **the editor becomes the diff** (compare mode). Read it in four
   places:

   - **The Explorer.** Every changed fn is badged `+`/`±`/`−`, with a
     one-line digest under the row (`string: main version (there
     branch version)`); namespaces carry `+n ±n −n ∿n` summaries. Fns
     that exist only on the compared branch show as dimmed ghost rows
     (click one to switch there).
   - **The canvas.** Changed cards and their changed args ring, and
     the change is written ON the node: the value the other branch
     holds sits under yours, struck through (`there: branch version`);
     a renamed fn shows its other name on the card; an arg you bound
     here only rings green.
   - **The inspector.** Selecting a changed fn shows its exact
     `old → new` fields in the **diff panel** — with 💬 anchors for
     line-comments.
   - **The chip.** `Δ vs tutorial-branch · 1` appears by the branch
     chip — the number is how many fns differ. It survives reloads,
     so "always see my drift vs main" is one click. Its menu is the
     review cockpit: **💬 Review & comments**, **📤 propose** the
     current branch and **⇢ merge** the compared branch in.

6. Two things make it a diff of the GRAPH rather than of rows:

   - **Changed inside (`∿`).** A fn whose own rows are equal on both
     branches but which INHERITS a change — its parent's binding was
     retuned, a fn it references differs, a type it uses moved —
     carries a dashed ring and a `∿` badge (the Explorer row says
     `∿ via <fn>`). Click the badge: an ancestor is revealed inside
     the card, at the level that holds it, so you see its `Δ` rows
     in the context of THIS graph; a referenced fn opens as the root.
     The `∿ inside` lens chip turns these marks off.
   - **A replaced branch of the graph.** When an arg points at one
     fn here and another there (`⋯-bind` it to a different fn on
     your branch), the canvas shows both: your side is the real
     card, and the compared branch's side hangs beside it as a
     dashed, dimmed GHOST — that fn and what it composes, read-only,
     joined to the arg by a dashed elbow. Click its head to fold it.

7. Under the kind chips the Explorer gains a **diff lens row**:
   `Δ changed` (show only what differs, auto-expanding the groups
   that hold them), `+`/`±`/`−` by change type, `Aa core` (hide
   edits that touch nothing but names and descriptions), `💬 notes`
   (mark fns that carry anchored review comments — a `💬2` rides
   next to the badge) and `fx` (only changes whose EFFECT SET
   differs — those carry an `effects: pure here · time there`
   mark, the strongest "this affects behaviour" signal). While any
   lens filter is on, the chip turns dashed and counts
   `visible/total` (say `· 1/3`) — a reminder that "no badges"
   means "hidden by the lens", not "no changes".
8. The diff isn't only about values — it shows the graph's SHAPE
   changing too. Each entry kind reads differently:

   | You did (on the branch) | The diff row says |
   |---|---|
   | edited a bound value | `± arg port` · `8080 → 9090` |
   | renamed / re-described the fn | `± fn` · `description: old → new` |
   | exposed a new arg (extended structure) | `+ slot retries` · `at position 2` |
   | bound an arg to another fn (an edge!) | `+ arg handler` · `ref → :my-handler` |
   | re-aimed an existing edge | `± arg handler` · `ref-fn-id: :a → :b` |
   | added/edited a list element | `± item 0 of nums` · `1 → 2` |
   | created a whole fn | a `−` ghost row here ("only on <branch>"), a `+` group in ITS branch's view |

   Try one: on `tutorial-branch`, extend some fn with a new child (or
   ⋯-bind an arg to a different fn), then compare again — the
   structural rows appear in that fn's inspector diff panel, and the
   same args ring on the canvas.
9. Exit compare mode: click the lit `Δ` again (or the chip's `×`).
10. From `main`, click `⇢` next to `tutorial-branch`. Confirm. The
    page reloads and `main` now sees your edit. (The guided tour
    leaves this click to you: a merged branch becomes part of `main`'s
    history and can no longer be deleted — [lesson 21](21-review.md)
    explains why.)

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
| `:interval`, `:interval-now` | Tick cadence is per-environment |
| `:env` | Env-var indirection is per-deployment |
| `:deploy-config` | A deployment setting is per-instance by definition |

Any fn-def parented from one of those inherits the flag. So this
fn-def…

```edn
{:name :my-web-server
 :parent :http-server
 :args {:handler :my-handler :port 8080}}
```

…is effectively branch-local because `:http-server` is. On a
merge, the resolver filters out its version rows on the target
branch; compare mode and the Review dialog mark such rows with
`📍 branch-local` (the inspector's diff panel carries it too), and
the post-merge alert names exactly what didn't propagate:

```text
2 branch-local fns did NOT propagate to main:
:my-web-server, :my-vault-secret
(Marked with 📍 in the diff.)
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

## Merge policy: `:forbid-invalid?`

A branch can opt in to a merge-time QUALITY gate. Pass
`"forbid-invalid?": true` when creating it (API-only for now —
the editor's branch popover doesn't expose the flag yet):

```bash
curl -X POST "$BASE/api/branches" \
  -H "Content-Type: application/json" \
  -d '{"name": "release", "forbid-invalid?": true}'
```

While either the SOURCE or the TARGET branch carries recorded
type diagnostics (what the Explorer's ⚠ type errors lens marks — Lesson 16),
merging INTO such a branch is refused with a 409
(`:merge-protection-violation`) whose message names the broken
fns: "Merge blocked: target branch forbids invalid fns —
unresolved type errors on: …". Fix the flagged fns (or merge into
a branch without the flag) and retry.

Contrast with `:branch-local?` above — that's a different KIND of
gate: `:branch-local?` is per-FN and silently SKIPS config-like
fns while the merge succeeds; `:forbid-invalid?` is per-BRANCH
and blocks the WHOLE merge while type errors exist anywhere on
either side. One scopes what propagates; the other enforces
when propagation may happen at all.

## Try it (sticky-local edition)

1. On `main`, find `:web-server` (the editor's own server). Note
   its port (8080).
2. Fork to `feat-dev-server`. On the new branch, copy `:web-
   server` to a new fn-def parented from `:http-server`, port
   9001.
3. Still ON `feat-dev-server`, press `Δ` on the `main` row
   (compare mode) and select your new fn — it is "added here", and
   the inspector's diff panel shows a `📍 branch-local` badge (the
   Review dialog's change list carries it too; from `main` the fn
   is only a ghost row — clicking it offers to switch over).
4. Merge `feat-dev-server` → `main`. The alert names your fn as
   skipped. Check `main` — it's not there.
5. Switch back to `feat-dev-server` — still there. The branch
   that produced it keeps it.

## What we glossed over

- Per-branch services (running `:my-web-server` as a managed
  service on dev while `:web-server` runs on main) — Lesson 32.
- How the resolver handles deep branch chains (B forked from A,
  C forked from B, merge edges everywhere) — see
  [docs/VERSIONING.md](../VERSIONING.md).
- The conflict-resolution shape returned by the API — see
  `:_merge-apply-err-conflict` in the branches package.

## Next

[Lesson 21 — Review: propose, approve, and the protected merge](21-review.md):
the merge you just made, made conditional on someone's approval.
