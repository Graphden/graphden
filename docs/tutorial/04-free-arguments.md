# Lesson 04 — Free arguments and how they propagate

**Goal**: by the end of this lesson you understand what makes a
slot "free", how that freedom flows up through composition into
the caller's interface, and how to write reusable templates by
leaving slots intentionally unbound.

**Concepts introduced**: `free argument`, `bound vs free slot`,
`free-arg propagation`, `template fn-def`, `:as` rename, `free-arg
strip`.

## What "free" means

A slot is FREE on a fn iff no fn in its `:parent-ids` BFS
closure binds it. The slot is declared somewhere (a base-fn,
type-row, or rename), but no ancestor has supplied a `:value`
or `:ref-fn-id` for it. The runtime can't execute the fn
without that slot being filled — so the CALLER (whoever
invokes the fn) has to supply it.

```edn
;; :str-len is a base-fn declaring :string as a slot.
{:name :str-len
 :args {:string {:type :text}}
 :return-type :int}
```

`:str-len` has ONE slot. No fn binds it (it's a base-fn — there's
no `:parent-ids` to walk). So `:string` is free. To execute
`:str-len`, the caller passes `{:string "hello"}`.

Now compose:

```edn
{:name :greet-len
 :parent :str-len
 :args  {:string :greeting}}   ;; binds :string to a ref

{:name :greeting
 :parent :const
 :args  {:value "Hello, world!"}}
```

`:greet-len` BINDS `:string` to a ref `:greeting`. Now the
caller of `:greet-len` doesn't need to supply `:string` —
graphden invokes `:greeting` at runtime and feeds the result
to `:str-len`. So `:greet-len` has ZERO free args. `▶ Run`
opens the run popover saying *"No free arguments — click Run
to invoke"* — no form to fill, one confirming click.

## Free args bubble up

This is the load-bearing property. Watch:

```edn
{:name :longer-than
 :parent :gt
 :args  {:nums [:str-len-of        ; nums[0] = the length (a ref)
                {:as :threshold}]}} ; nums[1] = threshold (free)

{:name :str-len-of
 :parent :str-len
 :args  {}}   ;; :string is left UNBOUND
```

`:gt` compares its `:nums` list pairwise (`nums[0] > nums[1] > …`),
so this reads "length is greater than threshold". What are
`:longer-than`'s free args?

- `:str-len-of` has `:string` free (it bound nothing to
  `:str-len.:string`).
- `:longer-than` refs `:str-len-of` as the first list item. So
  `:longer-than` INHERITS the free-ness: `:string` propagates up
  as a free arg of the caller.
- `:longer-than` ALSO has `:threshold` — the second list item,
  surfaced as a free arg via `:as`.

So `:longer-than`'s public interface is `{:string ... :threshold
...}`. Two free args. The editor's free-arg strip at the bottom
of the card lists exactly those.

## `{:as :name}` — declare a free arg explicitly

The most common way to surface a slot as a free arg is the
`:as` rename — it keeps the slot UNBOUND while giving it a
public name:

```edn
{:name :json-route
 :parent :route
 :args  {:method {:value :get}
         :path   {:as :path}        ; :path is free
         :handler {:as :handler}}}  ; :handler is free
```

`:json-route` is now a TEMPLATE. Callers fill in `:path` and
`:handler`; everything else is baked in (`:method = :get`).

```edn
{:name :get-users-route
 :parent :json-route
 :args  {:path "/users"
         :handler :list-users}}
```

`:get-users-route` binds both free args. It has zero free args
of its own.

## The free-arg strip

Open any composed fn-card. At the BOTTOM of the card is a
strip showing the free args:

```
free args: ?path  ?handler
```

Each `?name` is one free arg. Open the row's `⋯` popover and
click ▶ Run → the execute popover form has exactly these
fields. The strip is
read-only; it's surfaced from the storage chain, not
configurable per-card.

If you see `(none)` instead — every slot is bound, the fn is
"complete"; the run popover shows *"No free arguments — click
Run to invoke"* instead of a form.

## Free args + HOF

Lesson 06 covered HOFs in detail; the short version of how they
interact with free args:

For ITERATING HOFs (`:map`, `:filter`, …) the callback's
free args minus the structural lambda-param become free args of
the CALLER:

```edn
{:name :scale-each
 :parent :map
 :args  {:func {:parent :mul
                :args {:nums [{:as :item}      ; iteration param
                              {:as :factor}]}} ; free → surfaces up
         :coll {:as :nums}}}                   ; free → surfaces up
```

`:scale-each`'s free args are `:factor` and `:nums`. The `:item`
slot doesn't surface — `:map` fills it per iteration.

For ONE-SHOT HOFs (`:future`, `:assoc-fn`, `:invoke`) the
callback's single non-env-bound free arg becomes the lambda's
input; everything else surfaces as a free arg of the caller.

## Renaming a slot through the chain

Renames cascade. If `:str-len`'s slot is `:string`, and an
ancestor renames to `:input`, and the grandchild renames again
to `:text`, the final caller sees `:text`. The runtime walks
`source-slot-id` to resolve back to the original slot identity
(see lesson 03).

This is what makes reusable templates ergonomic: the public
name is what the caller sees, even if the inherited slot's
original name was something internal.

## A common gotcha — `{:value :keyword}` is a LITERAL, not a ref

```edn
{:name :almost-template
 :parent :assoc
 :args  {:key {:value :foo}}}   ; :key is BOUND to the keyword :foo
```

`{:value :foo}` is a literal — the keyword `:foo`. NOT a ref to
the fn-def named `:foo`. To make `:key` free, use `{:as :foo}`:

```edn
{:name :real-template
 :parent :assoc
 :args  {:key {:as :foo}}}      ; :key is FREE, exposed as :foo
```

Same shape on the wire, completely different semantics. The
editor's chip color helps distinguish — literal chips and ref
chips render differently — but in raw EDN this catches people
out.

## Try it

1. Create a template:

   ```edn
   {:name :tutorial-status-response
    :parent :ring-response
    :args  {:status {:as :status}
            :body   {:as :body}
            :headers {:value {"Content-Type" "text/plain"}}}}
   ```

   Free args strip should show `?status  ?body`. `Content-Type`
   is baked in.

2. Specialize:

   ```edn
   {:name :tutorial-200-ok
    :parent :tutorial-status-response
    :args  {:status 200
            :body {:as :message}}}
   ```

   Free args strip now shows just `?message`. `:status` is
   pinned to 200; `:body` is renamed from internal `:body` to
   the public `:message`.

3. Run `:tutorial-200-ok` with `:message = "OK"`. Result is a
   Ring response map.

## What we glossed over

- **Optional vs required free args** — slots can be marked
  `:required false`. Optional frees default to nil; required
  ones must be supplied. See lesson 03's `:required`
  monotonicity rule.
- **Type checking free args** — the executor validates each
  supplied value against the slot's declared type at call time.
  Mismatch → `:execution-error/arg-type-mismatch`. Lesson 09.
- **Closure-capture** — free args propagate through `:fn`-typed
  slots in a way that requires special handling. Lesson 06.

## Next

Lesson 05 — Types ([already written](05-types.md))
