# `wt` — parallel feature dev with a merge queue

Run several features at once, each in its own git worktree with its own
Claude Code (or human) session, and land them on `develop` one at a time
through a serialized, fully-tested gate.

## Why

- **Independent dialogs.** One worktree = one branch = one session. You
  switch between sessions freely, give each its own instructions, discuss,
  answer follow-ups. Sessions don't see each other's files.
- **Fast loop stays parallel.** Inside each worktree the inner loop is
  `bb ci` (linters + unit). It already takes a *per-checkout* flock, so N
  sessions run `bb ci` simultaneously without stepping on each other.
- **Heavy checks are serialized.** `integration` + `e2e` + `coverage`
  need `bb rebuild` (global `graphden-executor:latest` + the shared
  Docker stack), so exactly one may run at a time. `wt merge` enforces
  that with a machine-wide `flock` — the lock *is* the queue.
- **`develop` can never go red from a merge.** The gate merges the
  latest `develop` **into** the feature and tests the *merged* result
  before advancing `develop` (the "merge train" invariant — two branches
  green in isolation can still break together).

## The queue is stateless

The lock knows nothing about who is waiting, so you add and drop
worktrees whenever you like: spin one up when you remember a new task,
drop it the moment it lands, change your mind and drop it unmerged.
Nothing tracks a fixed roster.

## Commands

Run from anywhere (the script finds the repo); worktrees land in
`../graphden-wt/<name>`.

```bash
dev/wtq/wt new <name>       # branch feature/<name> off develop + a worktree
dev/wtq/wt list             # every worktree: branch, ahead/behind develop, dirty, lock holder
dev/wtq/wt merge [--no-e2e] # RUN INSIDE a worktree: queue -> gate -> land on develop
dev/wtq/wt status           # queue lock holder + recent gate logs
dev/wtq/wt log <name>       # tail the latest gate log for a feature
dev/wtq/wt drop <name> [-f] # remove a worktree + branch (must be merged; -f discards)
```

## Typical flow

```bash
dev/wtq/wt new auth-refactor
cd ../graphden-wt/auth-refactor
claude                      # dedicated session; edit, `bb ci`, iterate

# ... meanwhile, from the main checkout, spin up another:
dev/wtq/wt new css-tokens
# ... a session in ../graphden-wt/css-tokens works in parallel ...

# when auth-refactor is ready, from ITS worktree:
dev/wtq/wt merge            # blocks if another merge is in flight (the queue),
                            # then: merge develop -> bb ci -> bb rebuild ->
                            # integration -> e2e -> coverage -> ff develop -> push
# GREEN  -> `dev/wtq/wt drop auth-refactor`
# RED    -> fix on the branch, `dev/wtq/wt merge` again
# CONFLICT during "merge develop" -> resolve in the worktree, commit, re-run
```

## Gate stages (per queue entry, in order, fail-fast)

1. `fetch` + fast-forward local `develop` from `origin`
2. **merge `develop` -> feature** (conflict here bounces back to you)
3. `bb ci` — lint + unit on the merged result
4. `bb rebuild` — bake the merged code into `graphden-executor:latest`
5. `bb test-integration`
6. `bb test-e2e` (skip with `--no-e2e` for a docs-only / low-risk change)
7. `bb coverage`
8. **fast-forward `develop` -> feature** + `git push origin develop`

Any failure leaves `develop` untouched and hands you the branch back with
the full log (`wt log <name>`).

## Notes / requirements

- Needs `flock` (util-linux) — present on Linux.
- The gate **inherits your shell env**, so `export` anything `e2e` /
  `coverage` / `push` need (`AUTH_TOKEN`, `GITHUB_TOKEN`, ssh-agent for
  the `origin` push) before `wt merge`.
- The main checkout (the one holding `develop`) must have no uncommitted
  **tracked** changes when a gate runs — untracked files are fine. Keep
  it as the clean "develop holder"; do feature work in worktrees.
- Coordination state (lock, `holder`, `logs/`) lives in
  `$(git rev-parse --git-common-dir)/wtq/` — shared across all worktrees,
  never committed.
- `--no-e2e` is an escape hatch for changes with no runtime surface
  (docs, comments); it still runs lint + unit + integration + coverage.
