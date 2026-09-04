# Lesson 14 — Tests: the `tests` namespace

**Goal**: by the end of this lesson you can write tests for your
fns — as ordinary fns in a `tests` namespace — run them, read their
pass/fail dots in the sidebar, and understand when graphden re-runs
them for you automatically.

**Concepts introduced**: the `tests` namespace segment, `:assert`,
`:assert-eq`, test statuses ("stale by construction"), the `tests`
lens, the diagnostics bar's Tests panel, auto-run on writes.

## What a test is

There is no `test` entity and no `is-test` flag. A **test** is an
ordinary named fn that lives in a namespace whose dotted path
contains the segment `tests` — `tests.parser`, `myproj.tests`,
`myproj.tests.api` all qualify (`testsuite` does not; matching is by
segment, not substring). Names starting with `_` are the tests
namespace's private helpers, not tests.

A test **passes** when it executes without a throw. Two base-fns
give you the throw:

| Base-fn | Args | Fails when |
|---|---|---|
| `:assert` | `:value` | the value is falsy (nil / false) |
| `:assert-eq` | `:actual`, `:expected` | the two differ (Clojure `=`) |

Neither takes a message — the *test's name* is the label of the
invariant, and a failed `:assert-eq` carries both operands in its
error data.

One more rule: a test must have **all free args bound** — the runner
executes it with no arguments. A test with unbound args shows up as
`not-runnable` instead of running.

## Try it

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=14)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

Say you have a fn `slugify` you want to pin down. In the running
editor:

1. Create a namespace `tests` (or `myproj.tests` under your project's
   root — that keeps it inside your workspace scope).
2. Click `+` in it to add a new fn. Name it `slugify-spaces` — the
   name states the invariant.
3. Set its parent to `:assert-eq`. The editor shows two free args.
4. Bind `:actual` to a ref of the fn under test with its input
   pinned — e.g. an inline `{:parent :slugify :args {:s "Hello World"}}`
   (any fn of your own works; `{:parent :add :args {:nums [2 2]}}`
   if you just want to see the machinery).
5. Bind `:expected` to the literal you expect — `"hello-world"`
   (or `4` for the `:add` variant).
6. Open the row's `⋯` actions popover and ▶ Run it once — a passing
   test returns its `:actual` value; a failing one errors with
   `assert-eq failed` and both operands in the error data.

Now the surfaces:

- In the Explorer's filter bar click the **✓ tests** chip — the tree
  focuses on your tests, each with a status dot: **green** passed,
  **red** failed, **grey** not run since its last edit.
- With the lens on, the chip row shows **▶ Run all** — every test
  on the branch runs, and the dots update one by one as each run
  lands (the lens keeps a live signal open, so nothing needs a
  refresh). The chip's number is how many tests the branch has.
- Select a test and open the Inspector's **Bindings** tab: a
  **Test** section shows the same status with the assertion's
  message on a failure, and **Run this test** runs just this one —
  the section re-renders from the run itself.

Break the test on purpose (change `:expected`) and watch the dot:
right after the edit it turns grey — the status is keyed to the
fn's *current version*, so an edited test honestly reads "not run
yet" instead of showing a stale green. A moment later it turns red
on its own: see below.

## Auto-run

When you edit any fn, graphden already knows — from the graph
itself — which tests depend on it (the reverse closure over ref /
parent / type edges). Those tests re-run automatically in the
background, debounced, and their dots refresh. Two deliberate limits:

- Only **pure** tests auto-run. A test whose closure declares
  effects (`:network`, `:db`, …) runs only from the Run buttons —
  nothing fires network calls just because you saved an edit.
- Big blast radii are capped (25 tests per pass) — the rest keep
  their grey dot until you press Run all.

This is the graph paying rent: no file globs, no watch-mode
heuristics — the dependency edges that *are* your program select
exactly the affected tests.

## What we glossed over

- Statuses are ordinary execution rows (Lesson 12's history) with
  the standard retention sweep — an untouched suite fades to grey
  after a week; re-run to refresh.
- The HTTP face (`POST /api/tests/run`, `GET /api/tests/status`) and
  the auto-run internals — [TESTS.md](../TESTS.md).
