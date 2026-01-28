# Extending Graphden

This guide covers common extension points in the Graphden system.

## Adding Base Functions

Base functions are the primitives that execute actual computations. They are registered at runtime and referenced by `fn-schema.name`.

### Step 1: Register the Base Function

```clojure
(require '[graphden.executor.interface :as exec])

;; Simple function that adds two numbers
(exec/register-base-fn!
  :add
  (fn [{:keys [a b]} ctx]
    (+ (exec/force-value a ctx)
       (exec/force-value b ctx))))
```

### Step 2: Create fn-schema in Storage

```clojure
(require '[graphden.storage-protocol.interface :as sp])

(let [fn-schema (sp/create-entity storage :fn-schema
                                  {:name "add"           ; Must match registered name
                                   :returned-type :int})]
  ;; Create arg-schemas for the function
  (sp/create-entity storage :arg-schema
                    {:fn-schema-id (:id fn-schema)
                     :name "a"
                     :type :int
                     :required true})
  (sp/create-entity storage :arg-schema
                    {:fn-schema-id (:id fn-schema)
                     :name "b"
                     :type :int
                     :required true}))
```

### Step 3: Create Function Instance and Execute

```clojure
;; Create a function instance
(let [fn-rec (sp/create-entity storage :fn
                               {:name "my-add"
                                :fn-schema-id (:id fn-schema)})
      ;; Set argument values
      _ (sp/create-entity storage :arg-value
                          {:owner-fn-id (:id fn-rec)
                           :arg-schema-id (:id arg-a)
                           :value 5})
      _ (sp/create-entity storage :arg-value
                          {:owner-fn-id (:id fn-rec)
                           :arg-schema-id (:id arg-b)
                           :value 3})
      ;; Execute
      ctx (exec/create-context {:storage storage})
      result (exec/execute ctx (:id fn-rec) {})]
  (println result)) ; => 8
```

### Argument Types

| Type | Clojure Type | Description |
|------|--------------|-------------|
| `:int` | Long | Integer value |
| `:text` | String | Text value |
| `:bool` | Boolean | Boolean value |
| `:numeric` | Number | Any numeric type |
| `:jsonb` | Map/Vector | JSON-compatible data |
| `:uuid` | UUID | UUID reference |
| `:fn` | UUID | Lazy function reference (not executed) |
| `:ref` | UUID | Function reference (executed on force) |

### Working with Thunks

Arguments are passed as thunks (lazy values). Use `force-value` to get the actual value:

```clojure
(exec/register-base-fn!
  :conditional-add
  (fn [{:keys [condition a b]} ctx]
    ;; force-value evaluates the thunk
    (if (exec/force-value condition ctx)
      (+ (exec/force-value a ctx)
         (exec/force-value b ctx))
      0)))
```

### Higher-Order Functions

For `:fn` type arguments, `force-value` returns the fn-id (UUID) without executing.

**Single-Argument Model**: Functions passed to HOF must have exactly **one required argument** (any name). This eliminates naming convention problems — users don't need to know that `map` expects `:item` or `reduce` expects `:acc`.

Use `make-single-arg-callable` to create a callable that automatically finds the single required argument:

```clojure
(exec/register-base-fn!
  :my-map
  (fn [{:keys [f coll]} ctx]
    ;; f is a :fn type - forcing returns fn-id (UUID)
    (let [fn-id (exec/force-value f ctx)
          items (exec/force-value coll ctx)
          ;; Create callable that passes value to the single required arg
          callable (exec/make-single-arg-callable ctx fn-id)]
      (mapv callable items))))
```

For `reduce`-like operations, the function receives a vector `[acc item]` as its single argument:

```clojure
(exec/register-base-fn!
  :my-reduce
  (fn [{:keys [f init coll]} ctx]
    (let [fn-id (exec/force-value f ctx)
          initial (exec/force-value init ctx)
          items (exec/force-value coll ctx)
          callable (exec/make-single-arg-callable ctx fn-id)]
      ;; Pass [acc item] as single value
      (reduce (fn [acc item] (callable [acc item])) initial items))))
```

**Example: apply-twice**

```clojure
(exec/register-base-fn!
  :apply-twice
  (fn [{:keys [f x]} ctx]
    (let [fn-id (exec/force-value f ctx)
          x-val (exec/force-value x ctx)
          callable (exec/make-single-arg-callable ctx fn-id)
          result1 (callable x-val)
          result2 (callable result1)]
      result2)))
```

## Execution Configuration

### Depth and Timeout Limits

```clojure
(exec/create-context
  {:storage storage
   :max-depth 1000      ; Maximum recursion depth (default: 1000)
   :timeout-ms 30000})  ; Execution timeout in ms (default: 30000)
```

### Runtime Free Arguments (call-site-args)

Functions may have "free" arguments — arguments without defined values in the database. These must be provided at runtime via `call-site-args`:

```clojure
;; For root function with free arg
(exec/create-context
  {:storage storage
   :call-site-args {arg-schema-id 42}})

;; For nested function via fn-result-value (call site)
(exec/create-context
  {:storage storage
   :call-site-args {[fn-result-value-id arg-schema-id] 100}})
```

**Key format:**
- **Root function**: `{arg-schema-id -> value}` — direct lookup by arg-schema-id
- **Nested function (call site)**: `{[fn-result-value-id arg-schema-id] -> value}` — lookup by fn-result-value + arg-schema-id

**Note:** Direct fn refs (HOF with type=:fn) cannot receive call-site-args. Only functions referenced via `fn-result-value` (call sites) can have their free args set externally.

### Error Handling

Common execution errors:

| Error Type | Cause |
|------------|-------|
| `:execution-error/fn-not-found` | Function ID not in graph |
| `:execution-error/missing-required-arg` | Required argument not provided |
| `:execution-error/type-mismatch` | Provided arg doesn't match type |
| `:execution-error/max-depth-exceeded` | Recursion limit reached |
| `:execution-error/timeout` | Execution took too long |

## Implementing Custom Storage

To implement a custom storage backend, implement these protocols from `graphden.storage-protocol.interface`:

### Required Protocols

1. **Storage** - Lifecycle management
   - `initialize` - Set up schema
   - `close` - Clean up resources

2. **StorageIntrospection** - Schema inspection
   - `current-entities` - List entity types
   - `current-enums` - List enum types

3. **StorageCRUD** - Data operations
   - `create-entity` - Create record
   - `read-entity` - Read by ID
   - `update-entity` - Update record
   - `delete-entity` - Delete record
   - `query-entities` - Query with conditions

4. **GraphConstraints** - Graph integrity
   - `validate-arg-schema-belongs-to-fn!`
   - `validate-no-dependency-cycle!`

5. **ExecutionGraph** - Graph resolution
   - `resolve-execution-graph` - Build execution graph

### Example Implementation Skeleton

```clojure
(ns my.custom-storage
  (:require [graphden.storage-protocol.interface :as sp]))

(defrecord CustomStorage [connection metadata]
  sp/Storage
  (initialize [this schema]
    ;; Create tables/collections for entities
    ;; Store metadata
    this)

  (close [this]
    ;; Close connections
    nil)

  sp/StorageCRUD
  (create-entity [this entity-name data]
    ;; Insert record, return with generated :id
    )

  (read-entity [this entity-name id]
    ;; Fetch by id, return nil if not found
    )

  ;; ... implement remaining protocols
  )

(defn create-storage [opts]
  (->CustomStorage (:connection opts) (atom {})))
```

### Contract Tests

Use the contract tests from `storage-protocol` to verify your implementation:

```clojure
(ns my.custom-storage-test
  (:require [graphden.storage-protocol.interface-test :as contract]))

;; Run contract tests with your storage factory
(contract/run-storage-tests create-my-storage)
```

## Graph Data Schema

The default graph schema includes these entities:

| Entity | Fields | Description |
|--------|--------|-------------|
| `fn-schema` | id, name, returned-type | Function type definition |
| `arg-schema` | id, fn-schema-id, name, type, required | Argument definition |
| `fn` | id, name, fn-schema-id | Function instance |
| `fn-result-value` | id, fn-id | Cached computation reference |
| `arg-value` | id, owner-fn-id, arg-schema-id, value | Argument value |

### fn-result-value Entity

The `fn-result-value` entity enables caching of function results within a single execution:

```clojure
;; Create a fn-result-value that points to a function
(let [frv (sp/create-entity storage :fn-result-value
                            {:fn-id (:id my-expensive-fn)})]
  ;; Multiple arg-values can reference the same fn-result-value
  ;; The function will execute once and the result will be cached
  (sp/create-entity storage :arg-value
                    {:owner-fn-id (:id fn-a)
                     :arg-schema-id (:id arg-schema-x)
                     :value (:id frv)})
  (sp/create-entity storage :arg-value
                    {:owner-fn-id (:id fn-b)
                     :arg-schema-id (:id arg-schema-y)
                     :value (:id frv)}))
```

**When to use:**
- Expensive computations that should run once
- Values that need to be consistent across multiple consumers
- Explicit control over caching behavior

### Customizing the Schema

You can extend the schema by modifying `graph-data-schema`:

```clojure
(ns my.extended-schema
  (:require [graphden.graph-data-schema.interface :as graph]
            [graphden.data-schema-protocol.interface :as ds]))

(defn build-extended-schema [builder]
  ;; Start with base graph schema
  (let [schema (graph/build-schema builder)]
    ;; Add custom entities
    (-> schema
        (ds/add-entity builder :my-entity
                       {:id {:type :uuid :primary-key true}
                        :name {:type :text :nullable false}
                        :data {:type :jsonb :nullable true}}))))
```

## Best Practices

1. **Register base functions at startup** - Before any execution
2. **Use appropriate arg types** - `:fn` for HOF, `:ref` for computed values
3. **Set reasonable limits** - Adjust max-depth and timeout for your use case
4. **Handle errors gracefully** - Catch and log execution errors
5. **Test with contract tests** - Ensure storage implementations are correct
