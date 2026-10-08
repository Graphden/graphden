# Lesson 09 — Higher-order functions and `:fn`-typed slots

**Goal**: by the end of this lesson you can write a fn-def that
takes another fn as input, understand why `:fn`-typed slots
behave differently from other slots, and reason about
closure-capture (why `:request` from an outer Ring handler is
available inside a deeply-nested callable).

**Concepts introduced**: `:fn`-typed slot, `HOF`,
`closure-capture`, `lambda-params`, `iterating vs one-shot HOF`.

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

When a slot is `:fn`-typed, the implementation receives a callable
prepared by the executor, rather than a function ID. A reference to an
ordinary function becomes a callable without running its body. A reference
to a function whose return type is itself `[:fn …]` is evaluated to obtain
that returned callable. The implementation then invokes the callable —
once per element for `:map`, once with no input for `:future`, or as its
own contract specifies.

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

`:future` invokes `:greet` once, in a thread; the body's result
(`"starting up"`) is discarded, and `:startup-task` returns the
stopper-thunk.

The bind type-checks because fn-typed slots are subtype-checked
with a covariant return — `:greet` statically returns `:text`,
and `[:fn {} :text]` fits a slot wanting `[:fn {} :any]`; that is
what lets a plain const-thunk drive `:future` in
[Lesson 35](35-services.md).

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

## A function that returns a function

You can specialize a graph by inheritance and pass it directly as a
callback. You can also construct a callable at execution time, just as
in Clojure. Its declared return type tells the executor to evaluate the
constructor when another function needs the returned callable.

For example, an application can accept a database-query callable rather
than connection parameters. A constructor captures the connection
parameters; its returned function accepts one request record containing
SQL and query parameters:

```edn
{:name :query-sql :parent :get
 :args {:coll {:as :request :type {:sql :text :params [:list :jsonb]}}
        :key {:value :sql} :default nil}
 :return-type :text}

{:name :query-params :parent :get
 :args {:coll {:as :request :type {:sql :text :params [:list :jsonb]}}
        :key {:value :params} :default nil}
 :return-type [:list :jsonb]}

{:name :query-body :parent :sql-query
 :args {:sql :query-sql :params :query-params}
 :lambda-params [:request]}

{:name :query-constructor :parent :const
 :args {:value {:type [:fn {:request {:sql :text :params [:list :jsonb]}}
                       [:secret [:list :jsonb]] #{:db :network}]}}
 :return-type [:fn {:request {:sql :text :params [:list :jsonb]}}
               [:secret [:list :jsonb]] #{:db :network}]}

{:name :make-query :parent :query-constructor
 :args {:value :query-body}}
```

First declare the open function-typed `:value` slot on `query-constructor`,
then bind `query-body` on its child. Combining `:ref` and `:type` in one
binding instead asserts a type for the referenced function's evaluated
result; that is a different contract and fails this check.

The typed slot prepares `query-body` as a callable; `const` returns that
callable. `request` is its per-call input. The other
free arguments — `url`, `user`, and `password` — belong to the constructor
and are captured when it runs. Constructing the callable does not execute
a SQL query; invoking it does.

The callable's result keeps `:secret`: the SQL password is secret-typed,
and the existing taint rules also mark the query result. Consumers must
retain that contract; this constructor does not make SQL results public
or bypass trace and result redaction.

Separate `prod-query` and `test-query` functions can extend `make-query`
with different connection values. The shared application takes their
result in a function-typed argument. These are ordinary functions and
references, with normal type and effect checks; they introduce no new
environment entity. For merge behavior, see
[configuration and branches](23-branches.md#what-doesnt-merge-branch-local-fn-defs).
The existing `sql-query` implementation opens a connection per invocation;
this construction does not add connection pooling.

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

In the editor the declaration is the **Call-site params** row of
the Inspector's Overview (and, with *Details* on, the **λ chip** at
the end of the card's signature strip): `λ derived` until you say
otherwise, `λ []` for "everything captured", `λ request` for a named
list. Click it on a fn you own: **Derived** / **None — []** / **These,
in order** with a tick-box per free arg (the order you tick is the
order they are filled). Clearing back to *Derived* is a Save away.

Without that explicitness, a Ring handler whose ref chain happens to mention
`:request` would have `:request` swallowed as the one-shot
lambda input — breaking the wrap.

When authoring a graph, declare which inputs the callback receives on
each call if several free arguments could qualify. The remaining inputs
are captured. When authoring a base implementation, follow the callable
shape declared on its slot: the compiler uses that type together with
`:lambda-params` to prepare the argument mapping.

## Try it

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=09)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

1. Type `map` in the Explorer filter and click the `map` row
   (`core.hof`). Read the two slots: the `:coll` chip reads `['a]`
   — a list of anything; the `:func` chip reads `(item:'a) → 'b` —
   a callable taking `:item` and returning something else. Only the
   second one is a HOF slot — and `'a` is the SAME unknown in both:
   whatever one slot learns about it, the other learns too.
2. Click `⋯` on the `map` row, choose **Extend**, name it
   `tutorial-map`, then **Save**. (`map` itself is package-owned,
   so its own slots are read-only — you customize by extending.)
   Both inherited slots show a `+` on your card.
3. **Bind what you know first — the data.** Click the `+` on
   `:coll`, **Append literal**, type `graph`, **Save**; the `:coll`
   edge now fans out after its chip — a branch to `graph` and a branch
   to a fresh `+`, the list's next free slot. Click it, **Append
   literal**, `den`, **Save**. The card reads `graph, den`. (An
   empty list's first `+` also offers **Bind fn-ref (whole list)**:
   the slot then takes one fn's *result* as the entire list — how a
   pipeline feeds `:map` with the output of `:str-split`; lesson 18
   builds one.)
4. Now look at the `:func` chip: a moment ago it read
   `(item:'a) → 'b`; it reads `(item:text) → 'b`, and you never
   touched it. Two strings went into `:coll`, so `'a` is `text`, and
   the type variable carried that across to the slot you have not
   bound yet. Every value you set narrows the choices left.
5. Click the `+` on `:func`. Instead of the value form you saw in
   earlier lessons, the fn picker opens straight away: a literal is
   not a thing you can put in a callable slot. It states
   *Expected: (item:text) → b* — not `(item:a)`. Every fn the slot
   can take wears a ✓, grouped by namespace like the Explorer's tree,
   and the ones that take exactly one TEXT value come first in their
   group: `str-upper` from lesson 04 qualifies, a fn over numbers
   does not. Fns that would leave you more to wire carry an **Extra
   inputs** chip; constants that would ignore the item, an **Ignores
   the input** chip. Fns of other types are one toggle away at the
   bottom — and the moment you type a name, every match is listed,
   the incompatible ones dimmed with a ✗ (click one and the checker
   explains why). For a single-argument callable the argument's NAME
   does not matter — `map` hands each element to the callee's one
   free argument, whatever it is called. Type `str-upper` into the
   picker's filter: it is the **Exact match** on top; click its row.
6. An edge now runs from `str-upper` into `tutorial-map`'s `:func`
   — the callback is wired, and nothing has run. `⋯ → ▶ Run` →
   **Run** — nothing to fill in, every slot is bound:
   `["GRAPH" "DEN"]`. Two strings went in and each came out
   upper-cased: `str-upper` ran once per item, and you never called
   it — `map` did, handing each element in as its one argument.
   That is the whole HOF contract: the argument is passed unrun and
   the impl drives it.
7. `str-upper` is package-owned, so make a callable you can shape:
   filter for `str-upper`, `⋯` → **Extend** → `tutorial-upper` →
   **Save**. After a run the Inspector sits on its **Runs** tab —
   click **Overview**; it lists **Call-site params: λ derived**.
8. Click that row, choose **These, in order**, tick `string`,
   **Save**. It reads `λ string`: when a HOF hands
   `tutorial-upper` its argument, `string` receives it; anything
   else the fn had stays captured from the graph. With one free arg
   *derived* would have found the same answer — with two it would
   have refused, and this is where you say which.

Notice the order you worked in: the data first, the callback last.
Each value you bind is information about the slots still free, and
the picker for the next one reads it — fewer, better candidates every
step. Had you opened `:func` first, its picker would have said
*(item:a) → b* and offered every one-argument fn in the graph (the
reverse works too: binding `str-upper` first would have narrowed
`:coll` to `[text]`). Bind what you know first, choose what you don't
last; [docs/TYPES.md § Narrowing as a way of working](../TYPES.md#narrowing-as-a-way-of-working)
has the rules and the limits.

When a callable slot takes SEVERAL arguments, the picker matches by name, and
a callee of your own has to expose args of those names; `{:as :item}`
in `fns.edn` (or a rename on the edge label, lesson 04) is how you
give it one. graphden's own test for `map`,
`map-applies-the-callable-to-every-item` (`core.tests`), shows the
named form: a small fn adding 1 to `:item`, over `[1 2 3]`.
Graphden's `reduce` callback takes one `:pair` vector containing
`[acc item]`; it does not receive two separate positional arguments.

### Going further (fns.edn / MCP only)

An inline `{:parent …}` map in a `:fn`-typed slot anonymizes the
callback (the named alternative is `:double` above):

```edn
{:name :tutorial-double-each
 :parent :map
 :args  {:func {:parent :mul
                :args {:nums [{:as :item} 2]}}
         :coll [1 2 3]}}
```

Run it: `[2 4 6]`. Replace the constant `2` with `{:as :factor}`
and the card grows a free arg `:factor` — the Run pane asks for it
before running.

## What we glossed over

- **Effects propagating through HOF boundaries** — `:effects`
  declared on the callable lift onto the HOF's effective effect
  set. Lesson 16.
- **The `:secret` type-marker** — how taint flows through HOF
  refs without spilling. Lesson 16.
- **Pure-only callable slots** — a slot typed `[:fn … #{}]` (the
  trailing empty effect set: `:filter`'s `:pred`, `:swap`'s `:func`)
  accepts only a PURE callable; the picker ✗-marks an effectful one and
  the checker says why. Lesson 12 meets one.
- **`hof-wrap` / `hof-lambda-params` source** — the actual
  Clojure code that implements the dispatch lives in
  `executor/compile_eager.clj` (`hof-wrap`) and
  `executor/compile/renames.clj` (`hof-lambda-params`) if you
  want to dig.

## Next

[Lesson 10 — Composing pages from components](10-components-and-pages.md)
