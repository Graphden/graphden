# `wt` — parallel feature dev with autonomous agents + a merge queue

Run several features at once, each handled by an autonomous agent in its own
git worktree, and land them on `develop` one at a time through a serialized,
fully-tested gate.

## The hands-off flow (what you actually do)

You never invent branch names or set up worktrees. You launch a nameless agent,
describe the task, and it does the rest:

```bash
bb wt agent            # opens a claude session in a "discussion" phase
```

Then, in that session:

1. **You describe the task.** Discuss, refine, answer its questions. It writes
   no code yet.
2. **It self-registers.** Once aligned, the agent picks a branch name and runs
   `bb wt claim <name> "<summary>"` itself — creating `feature/<name>` + an
   isolated worktree and joining the pool. It cannot edit code before this.
3. **It implements**, keeping `bb ci` (lint + unit) green — its fast loop.
4. **It proposes to land.** On your OK it runs the gate (`bb wt merge`) in the
   background: merge develop → ci → rebuild → integration → e2e → coverage →
   fast-forward develop. Red/conflict bounces back and it fixes; green lands.
5. **It proposes to clean up.** On your OK it removes its own worktree +
   branch (`bb wt drop <name>`).

Launch as many `bb wt agent` sessions as you want, whenever you want — each is
an independent dialog you can switch between and give direction to. The two
moments an agent pauses for you are landing and cleanup (Rule 5 in `AGENT.md`).

## Why it's safe to run in parallel

- **Fast loop is parallel.** `bb ci` takes a *per-checkout* flock, so N agents
  run it simultaneously without stepping on each other.
- **Heavy checks are serialized.** `integration` + `e2e` + `coverage` need
  `bb rebuild` (the global `graphden-executor:latest` + shared Docker stack), so
  exactly one may run at a time. `bb wt merge` enforces that with a
  machine-wide `flock` — the lock *is* the queue; agents wait their turn.
- **`develop` can't go red from a merge.** The gate merges the *latest*
  `develop` into the feature and tests the *merged* result before advancing
  `develop` (the merge-train invariant — two branches green in isolation can
  still break together).
- **The queue is stateless.** The lock knows nothing about who is waiting, so
  agents can be created and dropped at any time.

## Commands

The main checkout `/root/projects/graphden` (on `develop`) is your control desk:
`bb wt agent`, `bb wt list`, `bb wt status`. Agents drive the rest from inside
their worktrees.

```bash
bb wt agent                     # launch a nameless autonomous agent (discuss -> self-claim -> work)
bb wt claim <name> [task...]    # (agent-invoked) register feature/<name> + worktree
bb wt list                      # every worktree: branch, ahead/behind develop, dirty, last RESULT
bb wt status                    # queue lock holder + recent gate logs
bb wt log <name>                # tail the latest gate log for a feature
bb wt merge [--no-e2e]          # (agent, inside a worktree) queue -> gate -> land on develop
bb wt drop <name> [-f]          # remove a worktree + branch (must be merged; -f discards)

# Manual escape hatch (you name it yourself, no agent):
bb wt new <name> [task...] [--start]   # create the worktree; --start also launches an agent
bb wt start <name>                     # launch an agent inside an existing worktree
```

## Requirements

- `flock` (util-linux) — present on Linux.
- The gate **inherits your shell env**, so `export` whatever `e2e` / `coverage`
  / the `origin` push need (`AUTH_TOKEN`, `GITHUB_TOKEN`, and an `ssh-agent`
  with your key) before an agent lands anything. Without ssh, the gate still
  runs and advances `develop` **locally**, but the final `git push` is skipped
  (it warns).
- The main checkout must have no uncommitted **tracked** changes while a gate
  runs (untracked files are fine). Keep it as the clean "develop holder"; all
  feature work happens in worktrees.
- Coordination state (lock, `holder`, `logs/`, `tasks/`, `results/`) lives in
  `$(git rev-parse --git-common-dir)/wtq/` — shared across all worktrees, never
  committed.
- `--no-e2e` is an escape hatch for changes with no runtime surface (docs,
  comments); it still runs lint + unit + integration + coverage.

## Files

- `dev/wtq/wt` — the tool (wired as `bb wt`).
- `dev/wtq/AGENT.md` — the operating contract every agent follows.
- `dev/wtq/README.md` — this file.
