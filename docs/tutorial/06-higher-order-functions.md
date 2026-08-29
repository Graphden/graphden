# Lesson 06 — Higher-order functions and `:fn`-typed slots

**Goal**: by the end of this lesson you can write a fn-def that
takes another fn as input, understand why `:fn`-typed slots
behave differently from other slots, and reason about
closure-capture (why `:request` from an outer Ring handler is
available inside a deeply-nested callable).

**Concepts introduced**: `:fn`-typed slot`,`HOF`,`closure-
capture`,`lambda-params`,`iterating vs one-shot HOF`.

## The two kinds of slot

So far every slot we've seen wanted a VALUE: a literal, a
ref-fn-id (to invoke and use the result), a sequence of items.
The executor force-derefs these — by the time the impl runs,
all args are computed values.

There's a SECOND kind of slot: one that wants a CALLABLE,
not a value. The slot's type carries `:fn` as its outermost
constructor:

```edn
{:type [:fn {:item a} b]}  ; a callable from `:item` of type `a` to `b`
```

When a slot is `:fn`-typed, the executor PASSES THE FN-ID
unchanged to the impl. The impl invokes the callable however it
likes — once per element of a sequence (for `:map`), once with
no input (for `:future`), N times in a loop, etc.

## `:map` — the canonical example

```edn
{:name :map
 :args {:func {:type [:fn {:item a} b]
               :description "Per-element transform."}
        :coll {:type [:list a]
               :description "Input collection."}}
 :return-type [:list b]}
```

`:func` is `:fn`-typed. Using it:

```edn
{:name :double-each
 :parent :map
 :args  {:func :double
         :coll [1 2 3]}}

{:name :double
 :parent :mul
 :args  {:nums [{:as :item} 2]}}
```

`:double` declares `:item` as its free arg via the `{:as :item}`
rename. When `:map` runs, it iterates `:coll` and invokes
`:double` once per element, passing each element AS `:item`.
The result is `[2 4 6]`.

If `:func` weren't `:fn`-typed, `:map` would just receive
`:double`'s already-executed value (which doesn't make sense
without an input) and crash.

## `:future` — non-iterating HOF

`:future` runs a callable EXACTLY ONCE, in a background thread.
Its `:body` slot is `:fn`-typed but the callable takes no
input:

```edn
{:name :future
 :args {:body {:type [:fn {} :any]
               :description "Thunk to run in background."}}
 :return-type [:fn {} :null]   ; a stopper-thunk: call it to interrupt
 :effects #{:process}}
```

A user fn-def that wraps `:future`:

```edn
{:name :startup-task
 :parent :future
 :args  {:body :greet}}

{:name :greet
 :parent :const
 :args  {:value "starting up"}}
```

`:future` invokes `:greet` once, in a thread, prints the
constant.

### Covariant return — fn-typed slots are subtype-checked

When you bind a fn-ref into a `[:fn args ret]` slot, the
binding-site type-check synthesizes the ref's static
signature `[:fn (ref's args) (ref's return)]` and runs
fn-subtype against the slot's expected shape. Return is
covariant: a ref returning `:int` satisfies a slot expecting
`[:fn {} :any]` because `:int ⊆ :any`.

The same applies above: `:greet` is `:const`-parented and
statically returns `:text`. The slot `:body` wants `[:fn {}
:any]`. The synthesized ref-type `[:fn {} :text]` is a
subtype (zero args match the slot's `{}`, `:text` ⊆ `:any`),
so the bind passes.

This is the structural reason the from-scratch service-eligible
probe recipe in [lesson 32](32-services.md) works — bind a
const-thunk to `:future.body`, the type-check accepts, the
runtime `hof-wrap`s the ref as the daemon's callable. Without
covariant return you'd need to box the thunk in a `:identity`-
parented shim just to satisfy the slot.

## Closure-capture: how `:request` propagates

This is the tricky part. Consider a Ring handler:

```edn
{:name :get-user
 :parent :json-handler
 :args  {:data :build-user-doc}}

{:name :build-user-doc
 :parent :assoc
 :args  {:map {} :key "id" :value :user-id-from-request}}

{:name :user-id-from-request
 :parent :get
 :args  {:coll {:as :request}
         :key  {:value :user-id}
         :default nil}}
```

`:request` is a free arg of `:user-id-from-request`. It needs
to come from somewhere — but `:build-user-doc` doesn't bind
it, and neither does `:get-user`.

Where does `:request` come from? From the HOF call site at the
top — `:http-server` invokes `:get-user` with `{:request
<ring-req>}`. The Ring server knows how to capture `:request`
when it calls the handler.

But how does it get FROM the top call site DOWN INTO
`:user-id-from-request`, several refs deep?

That's the **closure-capture** mechanism. At wrap time (when
`:get-user` is bound to the `:handler` slot of `:http-server`),
graphden walks `:get-user`'s ref-chain transitively and notes
EVERY free arg name encountered: `:request`, in this case. Each
of those names becomes a slot on the wrapped callable. At call
time, the executor supplies them as `{:request <ring-req>}`.

The user doesn't write any glue — the names propagate
automatically through ref chains AND through HOF boundaries.

## "A function that returns a function"?

Coming from Clojure you may reach for a factory — a fn that takes
parameters and returns the callable you then hand to `:map`. There is
no such step here, because a graph fn with unbound free args already
IS that returned callable: binding some of its args (`add-5 :parent
add :args {:a 5}`) is the factory call, the bound args are the
closure, and the still-free arg is the lambda parameter the HOF
supplies per element. Referencing a fn into a `:fn`-typed slot always
passes the fn itself, unrun — the executor never evaluates it first
to obtain another function. Partial application by inheritance
replaces currying, and the composition stays visible in the graph.

## Iterating vs one-shot

Two HOF flavours interact with closure-capture differently:

| Flavour | Examples | Per-call input shape |
|---|---|---|
| **Iterating** | `:map`, `:filter`, `:update-vals` | The structural slot is named `:item` for sequences (`:map`, `:filter`), `:value` for the map key/value HOFs (`:update-vals`, `:update-keys`), and `:pair` for `:reduce` (the `[acc item]` vector); the callable's own same-named arg (or its single unambiguous free) receives the element |
| **One-shot** | `:future`, `:assoc-fn`, `:invoke` | The structural slot is named `:arg` (a generic placeholder); the callable **declares** its call-site parameters via `:lambda-params` (`[]` = everything captured) |

When the callable has several candidate free args and no
authored `:lambda-params`, the compile refuses with
`:compile/ambiguous-lambda-params` naming the candidates — the
old guessing heuristic is retired (it silently mis-wired
captured callables). Declare the contract on the callable:

```clojure
{:name :my-handler
 :lambda-params [:request]   ; ← per-call inputs, in order
 ...}
```

Without that explicitness, a Ring handler whose ref chain happens to mention
`:request` would have `:request` swallowed as the one-shot
lambda input — breaking the wrap.

This sounds intricate. The good news: as a user writing
fn-defs, you don't think about it. The runtime handles it. The
gate just means "things-that-look-iterating use `:item`,
things-that-look-one-shot use `:arg`" — pick the matching name
in your base-fn impl and the dispatch picks the right behavior.

## Try it

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial={id})
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

1. Find `:map` in the editor. Its `:func` slot's type chip says
   `[:fn {:item …} …]` — the `:item` is the structural marker
   that classifies `:map` as iterating.
2. Create:

   ```edn
   {:name :tutorial-double-each
    :parent :map
    :args  {:func {:parent :mul
                   :args {:nums [{:as :item} 2]}}
            :coll [1 2 3]}}
   ```

   (`{:parent …}` inline-anonymizes the callback — see lesson
   01 for the named alternative.) Run it. Result: `[2 4 6]`.

3. Try replacing the constant `2` with a free arg:

   ```edn
   {:name :tutorial-multiply-each-by
    :parent :map
    :args  {:func {:parent :mul
                   :args {:nums [{:as :item} {:as :factor}]}}
            :coll [1 2 3]}}
   ```

   The card now shows a free arg `:factor`. Open the row's `⋯`
   popover, click ▶ Run — the
   editor asks you to supply `:factor`, then runs.

## What we glossed over

- **Effects propagating through HOF boundaries** — `:effects`
  declared on the callable lift onto the HOF's effective effect
  set. Lesson 13.
- **The `:secret` type-marker** — how taint flows through HOF
  refs without spilling. Lesson 13.
- **`hof-wrap` / `hof-lambda-params` source** — the actual
  Clojure code that implements the dispatch lives in
  `executor/compile/renames.clj` if you want to dig.

## Next

[Lesson 13 — Effects and the `:secret` type-marker](13-effects-and-secrets.md)
