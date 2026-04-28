# Extending Graphden

Low-level extension points. For **adding new base-fns / fn-defs to an
existing package**, see [PACKAGES.md](PACKAGES.md) — that's the common
case and the package system (`resources/packages/`) already handles
registration, sync, and ordering.

This document covers:

- [Higher-Order Functions (HOFs)](#higher-order-functions-hofs)
- [Implementing Custom Storage](#implementing-custom-storage)
- [Graph Data Schema](#graph-data-schema)
- [Base-fn Version Tracking (impl-hash)](#base-fn-version-tracking-impl-hash)

---

## Higher-Order Functions (HOFs)

A `:fn`-typed arg in an `fns.edn` declaration tells the compiler
"this slot receives a callable". At sync-time, `is-fn=true` is set on
the parent-arg; at compile-time, refs bound to such slots go through
`hof-wrap` instead of the normal thunk path.

### Wrap shape by free-arg count

`hof-wrap` inspects the wrapped fn-graph's leftover free args (names
the author chose for unbound slots). The compiler never inspects
specific names.

| Free args of wrapped fn | Callable shape | Impl invokes as |
|-------------------------|----------------|----------------|
| 0 | variadic, ignores input | `(f)` or `(f anything)` |
| 1 | single-arg | `(f value)` — value is bound to that one name |
| ≥ 2 | map-callable | `(f {:name1 v1 :name2 v2 …})` — keys chosen by impl, author's `{:as :…}` renames must match |

### Example: `map` (1 leftover — single-arg)

```edn
;; In fns.edn
{:name :map
 :args {:func :fn
        :coll {:type :jsonb :required false}}
 :return-type :any}
```

```clojure
;; In impls.clj
(defbase map-fn [func coll]
  (mapv func coll))   ; func is called (func item) per element
```

A user binds their unary fn-graph as `:func`; its one leftover free
arg (whatever name) receives each item.

### Example: `reduce` (1 leftover via vec packing)

```clojure
(defbase reduce-fn [func init coll]
  (reduce (fn [acc item] (func [acc item])) init coll))
```

Impl packs `[acc item]` as a single value. The user's fn-graph is
unary (leftover = `:pair` or similar) and destructures via
`:get :key 0` / `:key 1` inside.

### Example: `middleware` (2 leftovers — map-callable)

```clojure
(defbase middleware [name body]
  {:name name
   :wrap (fn [handler]
           (fn [request]
             ;; body is map-callable: two free args :request + :next-handler
             (body {:request request :next-handler handler})))})
```

The body fn-graph declares `{:as :request}` and `{:as :next-handler}`
renames somewhere; those become its two leftover free-args; compiler
produces a map-callable that impl populates.

### `make-single-arg-callable` — raw fn-id entry point

When a `:fn`-typed arg arrives as a raw fn-id UUID (e.g. in test code
that doesn't go through `hof-wrap`), use
`exec/make-single-arg-callable` to get the same behaviour as the
compile-time wrap.

---

## Implementing Custom Storage

Implement these protocols from `graphden.storage.protocol.core` to
create a new backend:

| Protocol | Purpose |
|----------|---------|
| `Storage` | `initialize`, `close` — lifecycle |
| `StorageIntrospection` | `current-entities`, `current-enums` |
| `StorageCRUD` | `create-entity`, `read-entity`, `update-entity`, `delete-entity`, `query-entities` |
| `GraphConstraints` | `validate-no-dependency-cycle!` |
| `ExecutionGraph` | `resolve-execution-graph` |

### Skeleton

```clojure
(ns my.custom-storage
  (:require [graphden.storage.protocol.core :as sp]))

(defrecord CustomStorage [connection metadata]
  sp/Storage
  (initialize [this schema] this)
  (close [this] nil)

  sp/StorageCRUD
  (create-entity [this entity-name data] …)
  (read-entity [this entity-name id] …)
  ;; …

  sp/ExecutionGraph
  (resolve-execution-graph [this fn-id] …))

(defn create-storage [opts]
  (->CustomStorage (:connection opts) (atom {})))
```

### Contract tests

```clojure
(require '[graphden.storage.protocol.contract-test :as contract])
(contract/run-storage-tests create-my-storage)
```

---

## Graph Data Schema

Two entity types only — by design (see PHILOSOPHY.md "Minimal
entities").

| Entity | Fields | Notes |
|--------|--------|-------|
| `fn` | `id, name, parent-id, namespace-id, return-type, impl-hash` | `parent-id=nil` → base-fn; `name=nil` → local fn |
| `arg` | `id, fn-id, source-id, via-fn-id, name, type, required, value, ref-id, is-fn, next-arg-id, prev-arg-id` | `source-id=nil` → primary arg; `next-arg-id`/`prev-arg-id` → sequence chain |

### Inheritance via source-id

A composed fn's arg points at a parent's arg via `source-id`, then
overrides with `value` or `ref-id`:

```clojure
;; Base
(sp/create-entity storage :fn
                  {:name "add" :parent-id nil :return-type :int})
(sp/create-entity storage :arg
                  {:fn-id base-fn-id :name "a" :type :int :required true})

;; Composed fn that binds a=5
(sp/create-entity storage :fn
                  {:name "add-5" :parent-id base-fn-id})
(sp/create-entity storage :arg
                  {:fn-id composed-id :source-id arg-a-id :value 5})
```

### Extending the schema

```clojure
(ns my.extended-schema
  (:require [graphden.schema.graph.schema :as graph]
            [graphden.schema.protocol.protocol :as ds]))

(defn build-extended-schema [builder]
  (-> (graph/extend-builder builder)
      (ds/add-entity :my-entity
                     {:id {:type :uuid :primary-key true}
                      :name {:type :text :nullable false}
                      :data {:type :jsonb :nullable true}})))
```

---

## Base-fn Version Tracking (impl-hash)

Each base-fn gets a SHA-256 `impl-hash` stored on its `fn` entity.
Registry sync uses it to detect impl changes between runs.

### What goes into the hash

- `:args` (names, types, required flags)
- `:return-type`
- `:impl-source` — the body forms captured by the `defbase` macro

### What changes the hash

| Change | Hash changes? |
|--------|---------------|
| Function body edits | ✓ |
| Arg type / arity change | ✓ |
| Return type change | ✓ |
| Whitespace, comments, map-key order | ✗ |

### Reading a fn's impl-hash

```clojure
(let [base-fn (sp/read-entity storage :fn fn-id)]
  (:impl-hash base-fn))
```
