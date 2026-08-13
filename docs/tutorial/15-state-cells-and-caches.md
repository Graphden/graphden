# Lesson 15 — State: cells, swap, and a graph-native cache

**Goal**: by the end of this lesson you can hold mutable state
*inside the graph* — a counter, an accumulator, a cache — that
survives across calls, built entirely from fn-defs with no Clojure.
You'll know when state lives for one call versus forever, and the
one scope limit to plan around.

**Concepts introduced**: `:atom`, `:cell`, `:swap`, `:reset`,
`:deref`, `:swap-conj`, per-call vs per-registry lifetime,
per-instance scope, the `:state` effect.

## Why state in a graph

Everything so far has been pure: a fn takes inputs and returns a
value. Sometimes you need to *remember* something between calls —
count requests, cache an expensive result, accumulate a log.

Graphden gives you `clojure.core`'s atom as base-fns, so the
remembering is composed in the graph and visible/editable in the
editor, instead of hidden in an impl.

| Base-fn | Clojure | What it does |
|---|---|---|
| `:atom`  | `(atom v)`      | A box holding `v`, **fresh each call** |
| `:cell`  | `(atom v)`      | A box holding `v`, **shared across calls** |
| `:deref` | `@a`            | Read the box's current value |
| `:swap`  | `(swap! a f)`   | Atomically replace the value with `(f current)` |
| `:reset` | `(reset! a v)`  | Replace the value with `v` (no read) |
| `:swap-conj` | `(swap! a conj x)` | Append `x` (a fn-def over `:swap`) |

`:atom` and `:cell` hold the same kind of box. The only difference
is **lifetime**, and it is the whole story of this lesson.

## `:atom` — one call's scratch space

An `:atom` fn-def hands back a **fresh** box on every top-level
`execute`. Within that one call, every reference to the same
`:atom` fn-def resolves to the *same* box (result-caching, lesson
04) — so several steps can share it — but the next call starts over.

That's exactly what you want for a per-request accumulator: a
transaction journal, a running total for one computation. It's the
box behind the `:try`-based rollback pattern you'll see in the CRUD
package.

## `:cell` — state that outlives the call

A `:cell` is allocated **once** and baked into the graph like a
constant (lesson 03's `:value`), so the *same* box is handed back on
every `execute`. Write to it in one call and the next call sees the
write. That's what turns three primitives into a cache.

### Try it: a hit counter

Three fn-defs. The middle one is the update function `:swap` will
apply — a plain `(current + 1)` where `:current` is the box's value,
supplied per-swap.

```edn
;; The persistent box, starting at 0.
{:name :hit-count
 :parent :cell
 :args  {:initial-value 0}}

;; The 1-arg update: :current in, :current + 1 out.
;; :add takes a :nums list; {:as :current} keeps that slot free so
;; :swap can feed the box's value into it each call.
{:name :_bump-one
 :parent :add
 :args  {:nums [{:as :current} 1]}}

;; Increment the cell and return the new count.
{:name :count-a-hit
 :parent :swap
 :args  {:a :hit-count :func :_bump-one}}
```

`:swap`'s `:func` slot is `[:fn {:current a} a]` — a 1-arg callable
`a → a`. `:_bump-one` fits: it reads `:current` and returns a number
of the same type. Now `▶ Run` (via `⋯`) on `:count-a-hit` (lesson 09) returns `1`,
then `2`, then `3` — it remembers.

Swap `:cell` for `:atom` in `:hit-count` and it returns `1` every
time: a fresh box per call.

### Try it: a graph-native cache

A cache is a `:cell` holding a map, a read that `:deref`s + `:get`s,
and a write that `:reset`s the map with one more entry. This is
exactly how the editor's own response cache is built.

```edn
;; The persistent map.
{:name :my-cache
 :parent :cell
 :args  {:initial-value {}}}

;; Snapshot the current map (used by both read and write).
{:name :_my-cache-now
 :parent :deref
 :args  {:a :my-cache}}

;; READ: (get @cell key) — nil on miss.
{:name :my-cache-get
 :parent :get
 :args  {:coll :_my-cache-now :key {:as :key} :default nil}}

;; The next map: current + one entry.
{:name :_my-cache-updated
 :parent :assoc
 :args  {:map :_my-cache-now :key {:as :key} :value {:as :value}}}

;; WRITE: install it. :reset returns the value it stored.
{:name :my-cache-put
 :parent :reset
 :args  {:a :my-cache :v :_my-cache-updated}}
```

`▶` on `:my-cache-get` with `key = "a"` → `nil`. `▶` on
`:my-cache-put` with `key = "a"`, `value = 1`. Then `:my-cache-get`
with `key = "a"` → `1`, in a *later* call — the write persisted.

> Keys come back as **strings**, not keywords, after the JSONB
> round-trip — `(get m "a")`, not `(get m :a)`. See lesson 03's note
> on literal keys.

## `:swap` vs `:reset` — which write

`:swap` reads-and-writes atomically: `(f current)` can't lose a
concurrent update. `:reset` just overwrites; two callers racing can
drop one write. Use `:reset` only when a lost write is harmless —
an idempotent cache (same key always maps to the same value)
recomputes the dropped entry next time, which is why the cache above
uses it. Reach for `:swap` (or `:swap-conj`) when the update depends
on the current value and must not be lost — like the counter.

## The `:state` effect

`:swap` and `:reset` carry the `:state` effect (lesson 07): they
mutate shared state. It shows on the effect strip of anything built
on them, so a reader can see at a glance that a fn writes state and
isn't pure. `:deref` (a read) and `:cell`/`:atom` (allocation) don't.

## The one scope limit

A `:cell` lives in **one executor process**. Run several pods behind
a load balancer (lesson 10's cardinality, [SCALING.md](../SCALING.md))
and each has *its own* cell — a write on pod A is invisible to pod B.
That's fine for a cache of identical, recomputable data (the worst
case is a recompute on the other pod), but it is **not** shared
state. For state that must be consistent across pods you need an
external store (Redis, a database row) — a `:cell` won't do it.

One more rule: `:cell` is persistent only when its `:initial-value`
is a **literal** (a compile-time constant, as in the examples). Bind
it to a fn-ref and there's nothing to bake once, so it quietly
falls back to `:atom` behaviour — fresh each call.

## What we glossed over

- *How* `:cell` bakes its box once and `:atom` doesn't — the
  compile-time-value mechanism in the executor's compile pipeline.
  You don't need it to use them; it's toured in the developer code
  tour ([docs/devtour](../devtour/README.md), executor block).
- The `:try` + `:atom` transaction-journal pattern in full — see the
  CRUD package's secret/entity write units.
- Cross-branch behaviour: a `:cell`'s box is per-compiled-registry,
  so two branches with identical graphs may share one — see
  [VERSIONING.md](../VERSIONING.md).

## Next

[Lesson 16 — Members: managing who is in your org](16-users-admin.md)
