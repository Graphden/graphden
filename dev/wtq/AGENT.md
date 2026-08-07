# Feature agent — operating contract

This project is human-directed: an engineer sets the direction, approves the
design, and reviews and owns everything that lands. Within that direction you
work autonomously on **one assigned feature** — take it from its spec to landed
on `develop`, in an **isolated git worktree**, in parallel with other agents in
their own worktrees. Own your feature end to end: fix the problems you find
rather than deferring them — that ownership is the point of this contract.

You were started in one of two ways:

- **`bb wt agent`** (nameless) — you begin in the main checkout in a
  *discussion* phase and must **claim your own workspace before writing code**
  (see Rule 0).
- **`bb wt start <name>`** — a worktree already exists for you; your task spec
  is at `bb wt task <name>`. Skip Rule 0 and go straight to the loop.

## Rules (hard)

0. **Claim before you edit.** If you have no worktree yet: discuss the task with
   the user until it is clear, then pick a short kebab-case branch name and run
   `bb wt claim <name> "<one-line summary>"`. It prints `WORKTREE: <path>`.
   `cd` there and do **all** work in that path. You may READ anywhere, but you
   **must not create, edit, or delete any file** (especially in the main
   checkout on `develop`) until you have claimed.
1. **Stay in your worktree.** Never `cd` into another worktree, never edit
   `develop` directly, never touch another agent's branch. Other agents change
   unrelated files in parallel — your view of the repo is your branch only.
2. **Never drive the SHARED stack by hand.** Do **not** run `bb rebuild`,
   `bb deploy`, `bb test-integration`, `bb test-e2e`, `bb coverage`, or push to
   `origin`. Those address the canonical instance (`graphden-executor` on
   :9002) and the canonical image tag — the one `bb test-e2e` boots. Driving
   them from a worktree fights the queue, steals the demo, and overwrites the
   tag other agents' suites run against. The landing gate (`bb wt merge`) owns
   all of it, serialized behind a lock. (`bb rebuild` / `bb deploy` now refuse
   to run in a worktree rather than let you find this out the hard way.)

   **You get your own instance instead** — isolated containers, volumes, image
   and ports, on a port block reserved for your branch:

   ```bash
   bb wt up      # build THIS branch + run it; prints http://localhost:<your-port>
   bb wt down    # stop it (keeps the DB volume)
   ```

   Use it whenever you need to see your change actually running — a UI change
   especially (see the `graphden-ui` skill: prove it in a real browser). It
   ends in `bb verify`, which compares the running build's `/version` against
   your tree, so a green `wt up` is proof the instance is running **your** code
   and not somebody else's. `bb wt drop` reclaims all of it.
3. **Follow the repo's rules** — `CLAUDE.md` and the relevant skills
   (`graphden-code-quality`, `graphden-packages-quality`, `graphden-ui`, …).
   Write it clean the first time.

   **Those rules can change while you work.** They reached you once — `CLAUDE.md`
   when your session started, this file in your first minutes — and nothing
   re-reads them. Meanwhile `develop` moves. So the gate checks: if `develop`
   has changed any path in [`dev/wtq/GOVERNANCE`](GOVERNANCE) since you last
   looked, it **refuses to land** and sends you back. Landing work done under
   rules that no longer exist is not a thing you can do by accident.

   ```bash
   bb wt ack     # prints the diff of what changed, records that you have seen it
   ```

   It prints the actual diff, not a summary — the point is that the new rules
   pass through your context on the way to being acknowledged. Read them, decide
   whether they change what you are doing, then re-run the gate.
4. **Commit as you go** — conventional-commit format, English messages.
5. **Land it yourself. Don't ask permission to finish.** When the feature is
   complete and `bb lint` is green, run the gate (`bb wt merge`) — then, once it
   is green, clean up (`bb wt drop`). Report what happened. You do not pause for
   a sign-off at either step: the gate cannot advance `develop` on a red result,
   and `wt drop` refuses a branch that is not merged, so neither step can lose
   work. Asking "shall I merge now?" of a finished, green feature is ceremony,
   and it stalls a serialized queue for as long as it takes a human to answer.

   **Do** stop and ask when a real decision is yours to make and the answer
   changes what you build: an ambiguous requirement, a trade-off with no obvious
   default, a change of scope you discovered mid-task. That is judgement, not
   ceremony.

## Loop

1. **Understand** the task. If it is ambiguous in a way that changes what you
   build (not just a detail with a sane default), **stop and ask the user** —
   don't guess load-bearing decisions.
2. **Implement** in small commits, inside your worktree.
3. **Fast feedback:** run `bb lint` (~1 min, all linters, no tests) after each
   batch of edits — it catches most gate-reds for pennies. Run targeted tests
   (`clojure -M:dev:test -m kaocha.runner --focus <ns>`) around the code you
   actually changed. A full local `bb ci` before queueing is OPTIONAL when you
   are the only agent in the pool — the gate re-runs `bb ci` on the merged
   result anyway (diff-scoped via `--since`: checks whose `:relevant` paths in
   `scripts/checks.edn` saw no change are skipped visibly; `WTQ_CI_SKIP=a,b`
   force-skips a stage when you must, announced in the gate log), so a pre-queue full run only duplicates it. When
   `bb wt list` shows OTHER claimed agents, go `bb ci`-green before queueing:
   a red gate then burns a ~35-min serialized slot the whole pool waits behind.
   Either way, `bb ci` stays your only ALLOWED local heavy test command — never
   `bb rebuild` / `bb test-e2e` / `bb test-integration` from a worktree.
4. **Land** — when the feature is complete and `bb lint` is green, run the
   gate. No sign-off needed (Rule 5):

   `bb wt merge` takes **~30–40 min** (merge develop → ci → build image →
   integration → e2e → fast-forward develop → advance the demo instance) —
   longer than a
   foreground command may run, so **launch it with `run_in_background: true`**
   and wait to be re-invoked when it exits. Then check the outcome:
   - **`✓ landed`** (exit 0, `bb wt list` RESULT `GREEN`) → feature is on
     `develop`. Go to step 5.
   - **CONFLICT** (merging develop into your branch) → resolve in your worktree,
     commit, re-run `bb wt merge` (background).
   - **gate FAIL** (ci/integration/e2e on the merged result) → read
     `bb wt log <name>`, fix on your branch (reproduce with a focused local
     run — `bb ci` for unit reds, a single `node <file>.test.js` against
     `bb wt up` for e2e reds), re-run the gate. Iterate until green. Never
     weaken a test or skip a check to go green.
   - **FLAKE in the gate's e2e** (failed once, passed on retry) — the gate
     runs `bb test-e2e` with `WTQ_FLAKE_STRICT=1` UNCONDITIONALLY, so at
     `bb wt merge` a retry-only pass (or an entity leak) is a RED result
     that bounces the branch: fix the flake, don't re-roll the dice. The
     first strict run proved the pattern — the flake it caught was a wait
     bound sized at the operation's median, not a race; size waits to the
     honest worst case (the poll still returns early). Green-on-retry
     remains only the AD-HOC default when you run `run-edit-tests.sh` /
     `bb test-e2e` by hand outside the gate.
   - If the queue is busy the gate blocks waiting its turn — expected; let the
     background run wait.

   **The gate is YOURS to watch.** Launching it is not the end of your job:
   poll its progress about once a minute until it lands — `bb wt watch <name>`
   does exactly this (60s ticks: log tail + host load, then the fresh RESULT).
   Concretely:
   - **No output / not starting** (no log growth for a couple of minutes, no
     RESULT, not visibly waiting on the queue lock) → tell the user what you
     observe NOW — a silently stalled gate wastes a serialized slot the whole
     pool waits behind. Check the obvious causes first: host load (a killed
     gate leaves no RESULT file — `bb wt merge` is idempotent, re-run it;
     it now waits for load headroom by itself, threshold `WTQ_LOAD_MAX`),
     a stale queue holder, Docker down.
   - **RED / FAIL in the log** → start fixing IMMEDIATELY, before being asked:
     read the failing check's output in the gate log, reproduce with a focused
     local run, fix on your branch, re-run the gate. Report what broke and
     what you are doing about it — don't sit on a red gate.
5. **Clean up** — once landed and you have nothing left to do: `cd` back to the
   main checkout first (you cannot delete the worktree you are standing in),
   then `bb wt drop <name>`. Report done.

## Notes

- The gate merges the **latest** `develop` into your branch before testing, so
  you are always validated against what other agents have already landed. The
  occasional real conflict or cross-feature break is expected — fixing it is
  part of the task.
- `bb wt log <name>` = full transcript of your last gate run. `bb wt list` =
  every agent's branch, drift vs develop, and last RESULT.
