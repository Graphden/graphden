<!-- markdownlint-disable MD013 -->
# Graphden — AI author context

You are editing a **Graphden** graph through the MCP tools. This document
teaches you the entity model and the conventions, so your proposals read
like the graph a human would have written. Read it before your first
mutation.

This is **not** `CLAUDE.md` (which is for developers working on graphden
itself). This teaches you to write *user* fn-defs.

---

## 1. The one idea

**Code is a graph in a database, not text in files.** You do not write
Clojure. You **compose existing functions** by declaring data:

```clojure
{:name :add-tax
 :parent :mul
 :args {:nums [{:as :amount} {:value 1.2}]}}
```

That is a complete program. `:add-tax` inherits `:mul`'s single `:nums`
list slot, pins the second entry of that list to `1.2`, and leaves the
first entry open as a free arg named `:amount` — so `:add-tax` is
"multiply your `:amount` by 1.2", callable as a one-argument function.
There is no function body to write; there is only *which function you
build on* and *what you bind*.

## 2. Two kinds of `fn`

| Kind | Shape | You write these? |
|------|-------|------------------|
| **base-fn** | wraps ONE Clojure/Java call; has an implementation | No — they're primitives (`:add`, `:if`, `:str`, `:map`, `:get`, `:http-request`, `:pg-query`, …) |
| **composed fn (fn-def)** | `:parent` (or `:parents`) + `:args`; NO implementation | **Yes — this is all you write** |

You never write a base-fn. If you find yourself wishing for "a function
that does X in one step", the answer is almost always to *compose*
existing base-fns, not to ask for a new primitive.

## 3. The fn-def map — every key

```clojure
{:name       :my-fn            ; keyword, unique per (namespace, name). Omit → anonymous (rare; you want names).
 :namespace  "app.orders"      ; optional dotted string; groups fns. Omit → top level.
 :parent     :base-or-fn       ; inherit this fn's slots. (or :parents [:a :b] for two axes — rare)
 :args       {:slot-name ...}  ; bind / customise inherited slots (see §4)
 :description "one line"}       ; optional but kind — shows in the editor
```

`:return-type`, `:effects`, refinement/type-row keys exist too, but for
composition you rarely set them: they are **inferred** from what you
build on. Bind slots; let the type checker compute the rest.

## 4. `:args` — binding slots

A slot is a named input a parent exposes. In `:args`, the KEY is the slot
name; the VALUE says how to fill it:

| You want | Write | Example |
|----------|-------|---------|
| a literal value | the value directly | `{:status 200}`, `{:port 8080}` |
| a **reference** to another fn | its `:name` keyword | `{:handler :my-router}` |
| a literal keyword (not a ref) | `{:value :kw}` | `{:tag {:value :active}}` |
| rename the slot as it surfaces | `{:as :new-name}` | `{:x {:as :price}}` |
| leave it OPEN (a free arg) | omit the key entirely | — |

**The reference trap (this is why you write EDN, not JSON):** a bare
keyword in a value position is a *reference* to another fn. `:my-router`
means "wire in the fn named `:my-router`". If you actually want the
literal keyword `:active` as data, you must write `{:value :active}`.
JSON cannot express this difference — that is exactly why the
`upsert-fn-defs` tool takes EDN text.

## 5. Slots, inheritance, free args

- A composed fn **inherits every slot of its parent(s)**, transitively.
- Each inherited slot you DON'T bind becomes a **free argument** of your
  fn — the thing a caller (or `execute-fn`) must supply.
- Bind a slot and it's fixed; the free-arg list shrinks by one.

So "make a specialised version of X" = "inherit X, bind the slots you
want fixed, leave the rest free". That is the whole mechanism.

```clojure
;; :add sums a sequence in its :nums slot.
{:name :add-10 :parent :add :args {:nums [10]}}
;; add-10 seeds :nums with [10]; a caller extends the sequence.
```

## 6. Control flow is data too

There is no `if`-statement; there is the `:if` base-fn, and you bind its
slots:

```clojure
{:name :fee-for
 :parent :if
 :args {:test :is-premium?      ; a ref to a predicate fn
        :then {:value 0}
        :else {:value 5}}}
```

`:cond`, `:and`, `:or`, `:map`, `:filter`, `:try` work the same way —
they are base-fns whose slots you fill. Wrapping a literal value so it
can be referenced is `:const` (`{:parent :const :args {:value …}}`).

## 7. Your workflow (the tools)

1. **`list-namespaces`** — see the shape of the graph.
2. **`search-fns`** / **`read-fn`** — learn the vocabulary. Before you
   compose on `:parent :foo`, `read-fn` "foo" to see its slots and their
   types. **Compose on what exists; don't invent parents.**
3. **`create-branch`** — ALWAYS, before any mutation. Name it for the
   task, e.g. `ai/add-order-total`. `main` is the human's; you work on a
   branch and never touch main.
4. **`upsert-fn-defs`** — write your fn-defs (EDN vector) INTO that
   branch. The same constraints a human faces apply (no dependency
   cycles, unique names, type-check), so a rejection is precise feedback
   — read it, fix the proposal, try again.
5. **`execute-fn`** — run what you built and check the result.
6. **`diff-branch`** — this is what the human reviews: added / modified /
   removed entities. Leave your branch clean and readable.

## 8. Naming & style (so your graph reads like ours)

- **Short names; context carries meaning.** Inside `app.orders`, call it
  `:total`, not `:order-total-calculator`. The namespace already says
  "order".
- **Name a fn-def when it's reused or is a real concept.** A throwaway
  one-off used once can be inlined as `{:parent :X :args …}` right where
  it's used, no name.
- **A `_`-prefixed name is a private helper** — a step that only means
  something next to its parent, the graph's equivalent of a local `let`.
  Use it for plumbing; use a plain name for things worth finding.
- **Don't fabricate types.** Bind slots and let inference compute
  `:return-type` / `:effects`. Set a type only when you're introducing a
  genuinely new contract.

## 9. Effects — what the cloud will and won't run

If your fn is executed in the hosted platform, only these effects are
allowed: reading/writing the graph DB, time, randomness, and in-graph
state. The external world — network, environment variables, files,
spawning processes — is **blocked** for tenant graphs. Compose within
that budget; if a step needs the outside world, it belongs to a
platform-provided base-fn, not your composition.

## 10. A complete worked example

Goal: "an order total = sum of line items, plus 20% tax".

```clojure
[{:name :subtotal
  :namespace "app.orders"
  :parent :add
  :description "Sum the :nums line-item sequence."}
 {:name :with-tax
  :namespace "app.orders"
  :parent :mul
  :args {:nums [{:as :amount} {:value 1.2}]}
  :description "Multiply :amount by 1.2 (20% tax)."}
 {:name :order-total
  :namespace "app.orders"
  :parent :with-tax
  :args {:amount :subtotal}
  :description "Line-item subtotal, taxed."}]}
```

Then `execute-fn "order-total" {:nums [10 20 30]}` → `72.0`
(subtotal `10+20+30 = 60`, then `× 1.2`). `diff-branch`
shows three fns added-in-target; the human reviews and merges.

---

**Remember:** you are not writing code that graphden runs — you are
describing, as data, how existing functions compose. Every rejection is
the type system or the constraint checker telling you the composition
doesn't hold yet. Read it, adjust the data, propose again.
