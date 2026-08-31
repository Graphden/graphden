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

```
Create              → type a name, click Create
                      (forks from the current branch)
Advanced            → (cloud/tenancy only) pick who can write the
                      new branch: everyone / only me / org admins
row → switch        → click a branch row to switch to it
Δ (diff)            → show the diff vs another branch
◐ (compare)         → COMPARE MODE: pick this branch as the second
                      one and the whole editor becomes the diff.
                      The picked row's ◐ stays lit; click it again
                      to clear the pick and exit.
✅ (approve)        → approve a proposed branch for merge
⇢ (merge)           → fold another branch into this one
⋯ (more)            → the labeled per-row menu:
                        📤 Propose for review — submit into its base
                        ⚙ Protection… — "push only via merge",
                          required approvals (0–3, one-tap segments),
                          count-own-approval. Works everywhere,
                          including single-user.
                        ⛨ Who can write… — (cloud/tenancy only)
                        × Delete branch
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

### Change proposals & review (📤 → ✅ → ⇢)

> Prefer to be shown? The review cycle is its own guided in-editor
> tour — protection, the 409 refusal, proposal and approval:
> [open the demo with tour 08b running](https://app.graphden.dev/?demo=1&tutorial=21)
> (no sign-up), or pick lesson 21 in the editor's account menu.

Beyond "who/how" you can require *review*: someone proposes a change,
someone (with rights) approves it, then it merges. All in the branch
popover, no separate "pull request" object:

- **📤 Propose** (in the row's ⋯ menu) marks a branch as submitted for
  review into its base. Proposed branches are the reviewer's to-do
  list (their ⋯ lights up, and a ✅ appears on the row).
- **⚙ Required approvals** on the *target* branch (e.g. `main`) — open
  its `⋯` → **⚙ Protection…** and set "Required approvals" to 1. A merge into `main` is
  then refused (409, *"requires 1 approval(s)…"*) until the proposal is
  approved.
- **✅ Approve** records your approval of a proposed branch. Who may
  approve = who may write the target (its write-policy roles, plus any
  explicit reviewer list set via the API). Once the count is met, the
  merge goes through.

Every proposal also carries a **comment thread** — open the Δ diff and
the conversation sits right under the change: leave a note, the author
replies, the reviewer approves when it's settled. A comment can also be
**anchored to one element of the diff** — hover a diff row and click its
💬 to pin the note to exactly that fn or arg (lesson 21 walks it).

Approvals are **content-aware**: if the proposed branch is edited after
it was approved, that approval is automatically dismissed (it went
stale) and the branch needs a fresh approval before it can merge —
just like GitHub dismissing stale reviews on a new push.

By default a proposal author's **own** approval counts, so a solo user or
a small team isn't locked out — propose → approve → merge works with one
person. A team that wants genuine four-eyes review unticks **"Count the
author's own approval"** in the target row's `⋯` → **⚙ Protection**
menu, and then a required approval must come from **someone other than
the author**.

### Try it — the plain flow

1. Click the branch chip. In the create row, type `feat-tutorial`
   and click **Create**. The editor reloads on `feat-tutorial`;
   the URL gets `?branch=feat-tutorial`.
2. Navigate to any fn-def with a literal value (e.g. one of the
   tutorial fns you made in lesson 01). Edit the value.
3. Switch back to `main`. The fn-def is unchanged — your edit
   lives only on `feat-tutorial`.
4. Open the branch popover, click `Δ` next to `feat-tutorial`.
   The diff modal groups the changes **by fn**: a `+`/`−`/`±` marker
   per row, and under a modified fn the exact args and fields that
   changed, shown as `old → new`. Click the row — the editor opens
   that fn on the canvas with the changed args ringed `Δ`.
   Prefer to *stay* in the diff? Click `◐` on the `feat-tutorial`
   row instead — picking the second branch IS entering compare mode:
   every changed fn is badged in the Explorer (namespaces carry
   `+n ±n −n` summaries), changed fn CARDS ring on the canvas along
   with their changed args, and the `◐ vs feat-tutorial` chip by the
   branch chip keeps the mode on — it survives reloads, so "always
   see my drift vs main" is one click. Clicking the chip opens the
   review cockpit: the full Δ diff, **📤 propose the current branch
   for review** (the merge-request act), **⇢ merge** the compared
   branch in, and the **type lens** — show only additions, removals
   or modifications, or "substantive only" (hide edits that touch
   nothing but names and descriptions). Exit = click the lit `◐`
   again (or the chip's `×`). You keep editing and running as usual;
   compare mode only annotates.
5. The diff isn't only about values — it shows the graph's SHAPE
   changing too. Each entry kind reads differently:

   | You did (on the branch) | The diff row says |
   |---|---|
   | edited a bound value | `± arg port` · `8080 → 9090` |
   | renamed / re-described the fn | `± fn` · `description: old → new` |
   | exposed a new arg (extended structure) | `+ slot retries` · `at position 2` |
   | bound an arg to another fn (an edge!) | `+ arg handler` · `ref → :my-handler` |
   | re-aimed an existing edge | `± arg handler` · `ref-fn-id: :a → :b` |
   | added/edited a list element | `± item 0 of nums` · `1 → 2` |
   | created a whole fn | its own `+` group under "Only in <branch>" |

   Try one: on `feat-tutorial`, extend some fn with a new child (or
   ⋯-bind an arg to a different fn), reopen `Δ` — the structural rows
   appear under the same fn group, and in compare mode the same args
   ring on the canvas.
6. From `main`, click `⇢` next to `feat-tutorial`. Confirm.
   The page reloads and `main` now sees your edit.

### Try it — with review required

1. Click `⋯` on the `main` row, choose **⚙ Protection…** and set
   **Required approvals** to `1` — `main` now requires one approval
   to merge into.
2. Create a second branch `feat-review`, edit a value on it, switch
   back to `main`.
3. From `main`, click `⇢` next to `feat-review`. It's refused —
   *"requires 1 approval(s)…"*.
4. In the `feat-review` row's `⋯` menu click **📤 Propose for
   review**, then click `✅` on the row (approve it). Its badge reads
   `0/1` before and `1/1` after.
5. `⇢` now merges cleanly, and the merged proposal drops off the
   review list. Reopen `main`'s `⋯` → **⚙ Protection…** and set
   Required approvals back to `0` to turn the requirement off.

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
type diagnostics (the content of the diagnostics bar's "Type errors" tab — Lesson 03),
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

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=20)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

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
  service on dev while `:web-server` runs on main) — Lesson 32.
- How the resolver handles deep branch chains (B forked from A,
  C forked from B, merge edges everywhere) — see
  [docs/VERSIONING.md](../VERSIONING.md).
- The conflict-resolution shape returned by the API — see
  `:_merge-apply-err-conflict` in the branches package.

## Next

[Lesson 12 — Executing a fn](12-executing-a-fn.md)
