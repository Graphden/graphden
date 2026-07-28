# Extending Graphden

Low-level extension points. For **adding new base-fns / fn-defs to an
existing package**, see [PACKAGES.md](PACKAGES.md) — that's the common
case and the package system (`resources/packages/`) already handles
registration, sync, and ordering.

This document covers:

- [Higher-Order Functions (HOFs)](#higher-order-functions-hofs)
- [Implementing Custom Storage](#implementing-custom-storage)
- [Graph Data Schema](#graph-data-schema)

---

## Higher-Order Functions (HOFs)

A `:fn`-typed slot in an `fns.edn` declaration tells the compiler
"this slot receives a callable". The slot's `type-fn-id` resolves to
the `:fn` primitive (overlayable per-fn via
`binding.type-override-fn-id`); at compile-time, refs bound to such
slots go through `hof-wrap` instead of the normal thunk path. The
legacy `:is-fn` boolean was retired — type IS the marker.

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
  (doall (map func coll)))   ; func is called (func item) per element; SEQ, not mapv→vector
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

### Read-only / remote backends (the minimal surface)

A backend that only needs to SERVE a graph — compile fn-defs and execute
them, without authoring — implements far less than a full read/write
backend. The executor's compile + execute path calls only:

- `StorageCRUD/query-entities` — with `{}`, `{:name v}`, `{:id [ids]}`,
  `{:fn-id [ids]}` shapes (equality on scalars, membership on vectors);
- `StorageCRUD/read-entity` and `StorageBatchCRUD/read-entities`;
- and it must *declare* `ExecutionGraph` to pass
  `executor.context/create-context`'s `satisfies?` gate — the method is
  never called (the compiled registry reads the tables and does the BFS
  in-process), so it can throw.

Everything else (writes, `GraphConstraints`, `StorageIntrospection`,
`StorageValueCodec`) is only reached by the authoring / schema paths.

`graphden.storage.remote.core/RemoteStorage` is exactly this: a read-only
leaf that bootstraps the whole graph over HTTP
(`GET /api/export/graph-rows`, org-scoped) into an in-memory index and
answers the reads from there. It backs the external / BYO executor — see
[SCALING.md § external executor](SCALING.md). Its writes throw
`:remote-storage/read-only`; the FaaS app path (`app_router` → `cr/execute`)
is read-only and works, while the `/api/execute` persistence path is a
hosted-editor concern.

---

## Graph Data Schema

Five entity types — see PHILOSOPHY.md for rationale and CLAUDE.md
for the up-to-date field list.

| Entity | Notes |
|---|---|
| `fn` | function or type-row; `parent-ids` ref-many for inheritance |
| `slot` | atomic `(name, type-fn-id)`; immutable post-create |
| `fn-slot` | junction `(fn-id, slot-id, position)` |
| `binding` | per-`(fn, slot)` overlay (value, ref-fn-id, rename-to, type-override-fn-id, terminal, list-{append,closed}, description) |
| `binding-list-item` | sequence content under a list-typed binding |

### Inheritance through parent-ids

A composed fn carries `parent-ids` and a binding shadows the
inherited slot:

```clojure
;; Base fn `add` has slot `a` (type :int)
(sp/create-entity storage :fn
                  {:name "add" :parent-ids [] :return-type-fn-id int-fn-id})
(let [slot-a (sp/create-entity storage :slot
                                {:name "a" :type-fn-id int-fn-id})]
  (sp/create-entity storage :fn-slot
                    {:fn-id add-id :slot-id (:id slot-a) :position 0})

  ;; Composed fn that binds a=5
  (let [add-5 (sp/create-entity storage :fn
                                {:name "add-5" :parent-ids [add-id]})]
    (sp/create-entity storage :binding
                      {:fn-id (:id add-5) :slot-id (:id slot-a) :value 5})))
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

#### Indexing a non-`:ref` column — `:indexed? true`

`:ref` fields get a btree index automatically (via `create-ref-indexes!`
on entity creation). To index a NON-`:ref` column — e.g. a plain
`:uuid` you filter reverse-references on — declare `:indexed? true` on
the field spec:

```clojure
:ref-fn-id {:type :uuid :indexed? true}
```

Two callbacks act on the flag:

- **`create-field-indexes!`** (postgres `ddl`) — issues
  `CREATE INDEX IF NOT EXISTS` for every `:indexed?` field when the
  table is created.
- **`ensure-field-indexes!`** (postgres `migration`) — runs on **every**
  migration pass, so a flag newly added to a field on an already-created
  table lands on existing dev/prod DBs (the entity-create callback never
  re-fires for them). It is idempotent (`IF NOT EXISTS`).

The schema validators accept `:indexed?` on any non-variant field spec.
Use it instead of a hand-written `CREATE INDEX` migration so the index
is declarative and travels with the schema.
