# Lesson 10 — Recursion: loops without cycles

**Goal**: write a fn that repeats itself in a graph where a fn is
structurally forbidden from referring to itself.

**Concepts**: the cycle constraint, `:fix`, the step fn, `:self` and
`:input`, the base case, `:invoke`, `*max-recursion-depth*`.

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=10)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu. The tour READS a recursion that already exists in your
> graph; the build-it-yourself version is below.

## The problem this solves

Lesson 06 covered the collection case: `map`, `filter` and friends
repeat something over a sequence you already have. This lesson is the
other kind of repetition — where the number of steps depends on what
you find as you go. Walking a parent chain until you hit a root.
Retrying until a condition holds. Flattening a nested structure.

In a normal language you would call the function from inside itself.
Here you cannot, and not by omission: graphden **rejects every cycle**
in the graph at write time, and again over the whole set at sync time
(lesson 02 hinted at this; [CONSTRAINTS.md](../CONSTRAINTS.md) has the
rules). A fn that referred to itself would be a cycle, so it is
refused. There is no flag to turn that off.

That constraint is load-bearing — it is what makes the graph safe to
compile eagerly, to lay out, and to reason about — so recursion gets an
escape that does not weaken it.

## `:fix` — the escape hatch that isn't a cycle

`:fix` is a base-fn with two slots:

| slot | what it takes |
|---|---|
| `:step` | a callable — the body of the loop |
| `:input` | the value the first iteration starts from |

`:fix` invokes your step with **two call-site args**:

- `:input` — the current iteration's value;
- `:self` — a one-argument callable that re-enters the step.

The step decides, each time, whether to return a value (the base case)
or to call `:self` with the next input. No fn in the graph points at
itself: the self-reference is created at RUN time, inside `:fix`'s
impl, and the graph stays acyclic. Nothing about the cycle check is
relaxed.

If the step never reaches its base case, the depth bound
(`*max-recursion-depth*`, default 1000) stops it with
`:recursion-error/max-depth-exceeded` — a typed error, not a blown
stack.

## The shape, in one worked example

Sum `1 + 2 + … + n`. The state we carry each iteration is a map:
`{:n <counter> :acc <running total>}`.

```edn
;; --- the two fields of the current state -------------------------------
{:name :_sum-n
 :parent :get
 :args  {:coll {:as :input} :key {:value :n} :default {:value 0}}}

{:name :_sum-acc
 :parent :get
 :args  {:coll {:as :input} :key {:value :acc} :default {:value 0}}}

;; --- the base case: n reached 0 ----------------------------------------
{:name :_sum-done?
 :parent :equal?
 :args  {:a :_sum-n :b {:value 0}}}

;; --- the next state: n-1, acc+n ----------------------------------------
{:name :_sum-next
 :parent :zipmap
 :args  {:keys [{:value :n} {:value :acc}]
         :vals [{:parent :sub :args {:nums [:_sum-n {:value 1}]}}
                {:parent :add :args {:nums [:_sum-acc :_sum-n]}}]}}

;; --- the recursive arm: call :self with the next state ------------------
{:name :_sum-recurse
 :parent :invoke
 :args  {:func {:as :self} :arg :_sum-next}}

;; --- the step: base case or recurse ------------------------------------
{:name :_sum-step
 :parent :if
 :args  {:test :_sum-done? :then :_sum-acc :else :_sum-recurse}}

;; --- the entry point ---------------------------------------------------
{:name :_sum-initial
 :parent :zipmap
 :args  {:keys [{:value :n} {:value :acc}]
         :vals [{:as :n} {:value 0}]}}

{:name :sum-to
 :description "Sum 1..n."
 :parent :fix
 :args  {:step :_sum-step :input :_sum-initial}}
```

Run `:sum-to` with `n = 4` → `10`.

Four things in that listing are the whole technique:

1. **`{:as :input}` and `{:as :self}`** are how the step reads its
   call-site args. They are free args (lesson 04) filled by `:fix` at
   each iteration, not bindings you set.
2. **The base case comes first.** `:if` is lazy (lesson 06's note on
   short-circuiting): when `:_sum-done?` is true, `:_sum-recurse` is
   never forced, so the recursion stops rather than running one extra
   level and discarding it.
3. **State is one value.** `:self` takes exactly one argument, so
   anything you need next iteration goes into that map. Two counters,
   an accumulator and a list — all one `:zipmap`.
4. **`:input` is renamed at the entry point.** `:sum-to` inherits
   `:fix`'s slots; `:_sum-initial` builds the starting state from a
   friendlier free arg `:n`, so the caller writes `:n 4` rather than
   constructing `{:n 4 :acc 0}` by hand.

## Reading a real one

Recursion is not a curiosity here — the branch chain your reads resolve
through is itself a `:fix` composition. Find `:branch-chain`
(`storage.branches`) in the Explorer and expand it:

- its parent is `:fix`;
- `:step` → `:_branch-chain-step`, an `:if`;
- the `:else` arm is `:_branch-chain-recurse` — an `:invoke` whose
  `:func` is `{:as :self}`;
- the `:then` arm returns the chain built so far.

Before `:fix` shipped, that walk lived in Clojure as a carve-out. It is
now graph like everything else, which means you can reparent a step of
it — cap the depth, stop at a tagged branch — without rebuilding the
server. That is the practical argument for the primitive: the platform
uses it for its own work.

## When NOT to reach for it

- **A sequence you already have** → `:map` / `:filter` / `:reduce`
  (lesson 06). They are clearer and the layout shows the shape.
- **A long-lived loop with effects** → a service (lesson 32).
  `:loop-until-interrupted` and `:schedule` exist for "keep doing this
  until stopped"; `:fix` is for computing a value.
- **Unbounded input** → the depth bound is 1000 by default. A walk
  over something that can be arbitrarily deep needs an explicit guard
  in the step, not a bigger bound.

## Try it

1. Build the `:sum-to` chain above. Seven fn-defs; the fastest way is
   the editor's `+` on each slot, but pasting the block through the
   MCP endpoint (lesson 28) is legitimate too.
2. Run `:sum-to` with `n = 4`. Expect `10`.
3. Break the base case deliberately: change `:_sum-done?` to compare
   against `-1` and run again with `n = 4`. The counter walks past
   zero, the bound trips, and you get
   `:recursion-error/max-depth-exceeded` — a typed error naming the
   limit, not a crash.
4. Put it back, then run with `n = 0`. The base case fires on the
   first iteration and returns `0` without ever invoking `:self`.
5. Open `:branch-chain` and find the same four parts in a fn the
   platform runs on every versioned read.

## Where this shows up next

- **Services** (lesson 32) — the other kind of repetition: effectful,
  supervised, and stopped by an interrupt rather than a base case.
- **Tests** (lesson 14) — a recursive fn is worth an `:assert-eq`
  covering the base case AND one recursive step; those are the two
  places this shape goes wrong.
