# graph-with-base-fns-memory

Complete graphden stack with in-memory backend. Includes storage, graph schema, executor, and all base functions pre-registered.

## Usage

```clojure
(require '[graphden.graph-with-base-fns-memory.interface :as gwbf]
         '[graphden.storage-protocol.interface :as sp]
         '[graphden.executor.interface :as exec])

;; Create complete environment
(def storage (gwbf/create-storage))

;; Base functions are already registered and synced
(sp/query-entities storage :fn-schema {})
;; => [{:name "add", :returned-type :numeric, ...} ...]

;; Create and execute functions
(let [add-schema (first (sp/query-entities storage :fn-schema {:name "add"}))
      my-fn (sp/create-entity storage :fn
              {:id (random-uuid)
               :name "my-add"
               :fn-schema-id (:id add-schema)})]
  ;; Execute with path-args
  (exec/execute storage (:id my-fn) {nums-arg-schema-id [1 2 3]}))
;; => 6

;; Cleanup
(sp/close storage)
```

## What's Included

1. **Memory storage** - In-memory implementation
2. **Graph schema** - fn, fn-schema, arg-schema, arg-value, fn-result-value entities
3. **Base functions** - 50+ functions registered:
   - Arithmetic: add, sub, mul, div, mod, inc, dec
   - Comparison: eq, neq, lt, lte, gt, gte
   - Logic: and, or, not
   - Strings: str, upper, lower, trim, split, join, etc.
   - Collections: first, rest, nth, count, conj, concat, map, filter, reduce, etc.
   - HOF: map, filter, reduce, some, every, sort-by, group-by, etc.

## When to Use

- Development and testing
- Single-process applications
- Prototyping and experimentation
- Unit tests (fast, no external dependencies)
