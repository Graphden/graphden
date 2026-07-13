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
   background: merge develop → ci → build image → integration → e2e →
   fast-forward develop → advance the demo instance. Red/conflict bounces back
   and it fixes; green lands.
5. **It proposes to clean up.** On your OK it removes its own worktree +
   branch (`bb wt drop <name>`).

Launch as many `bb wt agent` sessions as you want, whenever you want — each is
an independent dialog you can switch between and give direction to. The two
moments an agent pauses for you are landing and cleanup (Rule 5 in `AGENT.md`).

## Why it's safe to run in parallel

- **Fast loop is parallel.** `bb ci` takes a *per-checkout* flock, so N agents
  run it simultaneously without stepping on each other.
- **Heavy checks are serialized.** `integration` + `e2e` run
  against the canonical `graphden-executor:latest`, so exactly one may build it
  at a time. `bb wt merge` enforces that with a machine-wide `flock` — the lock
  *is* the queue; agents wait their turn.
- **Every agent gets its own instance.** `bb wt up` raises a private stack
  (own containers, volumes, image tag, and a reserved port block), so agents can
  click through their own changes in a browser in parallel without touching the
  demo or each other. The shared stack belongs to `develop` and to the gate;
  `bb rebuild` / `bb deploy` refuse to run in a worktree.
- **The demo only ever serves landed code.** The gate *builds* the image from
  the merged tree, tests it, fast-forwards `develop`, and only THEN advances the
  instance — still holding the lock, so two gates cannot race to redeploy it,
  and a red gate can no longer leave the demo running an unmerged branch.
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
bb wt merge [--no-e2e] [--deploy]  # (agent, inside a worktree) queue -> gate -> land on develop
                                #   --deploy also resets the develop DB schema on landing
bb wt up                        # (agent, inside a worktree) build + run THIS branch on its own ports
bb wt down                      # (agent, inside a worktree) stop it (keeps the DB volume)
bb wt drop <name> [-f]          # remove a worktree + branch + its instance, volumes, image, ports
bb wt gc                        # reclaim superseded / orphaned executor images
bb wt ack                       # (agent, inside a worktree) show what develop changed in the
                                #   rules the agent works under, and record that it has read them

# Manual escape hatch (you name it yourself, no agent):
bb wt new <name> [task...] [--start]   # create the worktree; --start also launches an agent
bb wt start <name>                     # launch an agent inside an existing worktree
```

## Requirements

- `flock` (util-linux) — present on Linux.
- The gate **inherits your shell env**, so `export` whatever `e2e`
  / the `origin` push need (`AUTH_TOKEN`, `GITHUB_TOKEN`, and an `ssh-agent`
  with your key) before an agent lands anything. Without ssh, the gate still
  runs and advances `develop` **locally**, but the final `git push` is skipped
  (it warns). To push develop yourself and keep the gate from even trying, set
  `export WTQ_NO_PUSH=1` — then you `git push origin develop` when you like.
- The main checkout must have no uncommitted **tracked** changes while a gate
  runs (untracked files are fine). Keep it as the clean "develop holder"; all
  feature work happens in worktrees.
- Coordination state (lock, `holder`, `logs/`, `tasks/`, `results/`) lives in
  `$(git rev-parse --git-common-dir)/wtq/` — shared across all worktrees, never
  committed.
- `--no-e2e` is an escape hatch for changes with no runtime surface (docs,
  comments); it still runs lint + unit + integration.
- **Coverage is NOT in the gate.** It has no fail-threshold and re-runs the unit
  suite `bb ci` already ran, so it could only fail where `bb ci` already had —
  ~18 minutes per landing for a number nobody reads at merge time. Measure it
  when you want to look at it: `bb coverage` from the main checkout.
- `--deploy` is for a branch that **changes the DB schema**: on landing, the
  develop instance's schema is dropped and re-seeded. The default keeps the
  data, so demo branches / secrets / executions survive an ordinary merge.
- **Disk.** Each `bb wt up` costs one ~350 MB executor image plus its volumes.
  `bb wt drop` reclaims them; the gate prunes the layers each build supersedes;
  `bb wt gc` sweeps whatever a hard-killed agent left behind.

## Rules can change under a running agent

An agent's rules reach it exactly once: `CLAUDE.md` is injected when its session
starts, `AGENT.md` is read in its first minutes, skills load on demand. Nothing
re-reads them — so an agent that has been working for hours may be following
rules `develop` has since replaced, and it would land that work without ever
noticing.

The gate closes that: it diffs the paths listed in `dev/wtq/GOVERNANCE` between
the commit the agent last acknowledged and `develop`, and **refuses to land**
while they differ. `bb wt ack` prints the diff (so the new rules actually enter
the agent's context) and records it. A resumed agent is told about the drift in
its kickoff prompt too.

This is why the list is an explicit file rather than a heuristic over the diff:
guessing "is this a rule, or just a doc?" is exactly how a rule change slips
past.

## Files

- `dev/wtq/wt` — the tool (wired as `bb wt`).
- `dev/wtq/AGENT.md` — the operating contract every agent follows.
- `dev/wtq/GOVERNANCE` — the paths that govern agent behaviour (see above).
- `dev/wtq/README.md` — this file.
