# Lesson 17 — Tests: the `tests` namespace

**Goal**: by the end of this lesson you can write tests for your
fns — as ordinary fns in a `tests` namespace — run them, read their
pass/fail dots in the sidebar, and understand when graphden re-runs
them for you automatically.

**Concepts introduced**: the `tests` namespace segment, `:assert`,
`:assert-eq`, test statuses ("stale by construction"), the `tests`
filter chip with its **▶ Run all** action, the Inspector's **Test** section,
auto-run on writes.

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
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=17)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

The tour builds the smallest honest example — a fn that computes
`2 + 2`, a test that pins its answer, then a deliberate lie. In the
running editor:

1. Something to test: filter `add`, `⋯ → Extend`, name the child
   `tutorial-sum`. Click the `+` on `:nums`, **Append literal**, `2`;
   click it again, append a second `2`. `tutorial-sum` now computes
   `2 + 2` (run it: `4`). In `fns.edn` terms:
   `{:name :tutorial-sum :parent :add :args {:nums [2 2]}}`.
2. Create a namespace `tests` (or `myproj.tests` under your project's
   root — that keeps it inside a namespace filter you may have on).
3. Click `+` in it to add a new fn. Name it `two-plus-two` — the
   name states the invariant.
4. Set its parent to `:assert-eq`. The editor shows two free args.
5. `:actual` wants a ref to the fn under test: click its `+`, choose
   **Bind fn-ref** and pick `tutorial-sum`. Then the `+` on
   `:expected`, **Bind literal**, `4`.
6. With every free arg bound the write itself triggers the auto-run
   (see below), so the status dot is green by the time you look.
   Open the row's `⋯` actions popover and ▶ Run it once anyway to
   see the result — a passing test returns its `:actual` value; a
   failing one errors with `assert-eq failed` and both operands in
   the error data.
7. Now lie to it: click the bound `4` on `:expected`, change it to
   `5`, **Save**. The dot goes grey the instant you save (this
   version has not run), then RED — the auto-run compared `4` with
   `5` and the assertion threw. Change it back to `4`: grey, then
   green again.

Any fn of your own works as the subject — `slugify` extended as
`slugify-hello` with its `:s` pinned to `Hello World`, expected
`hello-world`, is the same shape.

Now the surfaces:

- In the Explorer's filter bar click the **✓ tests** chip (or press
  `Space t`) — the tree
  focuses on your tests, each with a status dot: **green** passed,
  **red** failed, **grey** not run since its last edit.
- With the ✓ tests chip on, the chip row shows **▶ Run all** — every test
  on the branch runs, and the dots update one by one as each run
  lands (the chip keeps a live signal open, so nothing needs a
  refresh). The chip's number is how many tests of YOURS the branch
  has — and, in red, how many of them failed. The platform ships its
  own self-tests too (`core.tests`, `web.tests`, …): they show under
  the tree with their dots and run one at a time from the Inspector,
  but Run all leaves them out — they are graphden's tests, not your
  project's.
- Select a test and open the Inspector's **Bindings** tab: a
  **Test** section shows the same status with the assertion's
  message on a failure, and **Run this test** runs just this one —
  the section re-renders from the run itself.

That grey-then-red (or grey-then-green) in step 7 is the status
being keyed to the fn's *current version*: an edited test honestly
reads "not run yet" instead of showing a stale colour, and the
auto-run below is what settles it a moment later.

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

- Statuses are ordinary execution rows (Lesson 15's history) with
  the standard retention sweep — an untouched suite fades to grey
  after a week; re-run to refresh.
- The HTTP face (`POST /api/tests/run`, `GET /api/tests/status`) and
  the auto-run internals — [TESTS.md](../TESTS.md).
