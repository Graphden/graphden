# Extending Graphden

This guide covers common extension points in the Graphden system.

## Adding Base Functions

Base functions are the primitives that execute actual computations. They are registered at runtime and referenced by `fn.name` where `fn.parent-id` is nil (base-fn).

### Step 1: Register the Base Function

```clojure
(require '[graphden.executor.interface :as exec])

;; Simple function that adds two numbers
(exec/register-base-fn!
  :add
  (fn [{:keys [a b]} _ctx]
    (+ @a @b)))  ; @ dereferences the delay to get actual value
```

### Step 2: Create Base-fn in Storage

```clojure
(require '[graphden.storage.protocol.core :as sp])

;; Create base-fn (parent-id=nil means it's a base function)
(let [base-fn (sp/create-entity storage :fn
                                {:name "add"           ; Must match registered name
                                 :parent-id nil        ; Base function
                                 :return-type :int})]
  ;; Create args for the function
  (sp/create-entity storage :arg
                    {:fn-id (:id base-fn)
                     :name "a"
                     :type :int
                     :required true})
  (sp/create-entity storage :arg
                    {:fn-id (:id base-fn)
                     :name "b"
                     :type :int
                     :required true}))
```

### Step 3: Create Composed Function and Execute

```clojure
;; Create a composed function that inherits from base-fn
(let [my-fn (sp/create-entity storage :fn
                              {:name "my-add"
                               :parent-id (:id base-fn)})  ; Inherit from add
      ;; Create args with values (source-id references parent's args)
      _ (sp/create-entity storage :arg
                          {:fn-id (:id my-fn)
                           :source-id (:id arg-a)    ; Inherits from parent's "a"
                           :value 5})
      _ (sp/create-entity storage :arg
                          {:fn-id (:id my-fn)
                           :source-id (:id arg-b)    ; Inherits from parent's "b"
                           :value 3})
      ;; Execute
      ctx (exec/create-context {:storage storage})
      result (exec/execute ctx (:id my-fn) {})]
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
| `:fn` | UUID | Function reference (used with is-fn flag) |

### Working with Lazy Arguments (Delays)

Arguments are passed as Clojure delays (lazy values). Use `@` to dereference and get the actual value:

```clojure
(exec/register-base-fn!
  :conditional-add
  (fn [{:keys [condition a b]} ctx]
    ;; @ (deref) evaluates the delay
    (if @condition
      (+ @a @b)
      0)))
```

### Higher-Order Functions (HOF)

For HOF arguments, use `is-fn: true` on the arg. When `is-fn` is true, the ref-id is passed directly without execution.

**Single-Argument Model**: Functions passed to HOF must have exactly **one required argument** (any name). This eliminates naming convention problems — users don't need to know that `map` expects `:item` or `reduce` expects `:acc`.

Use `make-single-arg-callable` to create a callable that automatically finds the single required argument:

```clojure
(exec/register-base-fn!
  :my-map
  (fn [{:keys [f coll]} ctx]
    ;; f has is-fn=true - @ returns fn-id (UUID) without executing
    (let [fn-id @f
          items @coll
          ;; Create callable that passes value to the single required arg
          callable (exec/make-single-arg-callable ctx fn-id)]
      (mapv callable items))))
```

For `reduce`-like operations, the function receives a vector `[acc item]` as its single argument:

```clojure
(exec/register-base-fn!
  :my-reduce
  (fn [{:keys [f init coll]} ctx]
    (let [fn-id @f
          initial @init
          items @coll
          callable (exec/make-single-arg-callable ctx fn-id)]
      ;; Pass [acc item] as single value
      (reduce (fn [acc item] (callable [acc item])) initial items))))
```

**Example: apply-twice**

```clojure
(exec/register-base-fn!
  :apply-twice
  (fn [{:keys [f x]} ctx]
    (let [fn-id @f
          x-val @x
          callable (exec/make-single-arg-callable ctx fn-id)
          result1 (callable x-val)
          result2 (callable result1)]
      result2)))
```

### HOF Arg in Storage

```clojure
;; Create HOF base-fn
(let [map-fn (sp/create-entity storage :fn
                               {:name "map"
                                :parent-id nil
                                :return-type :jsonb})]
  ;; The 'f' argument is a HOF arg (is-fn=true)
  (sp/create-entity storage :arg
                    {:fn-id (:id map-fn)
                     :name "f"
                     :type :fn
                     :is-fn true        ; <- This makes it a HOF arg
                     :required true})
  (sp/create-entity storage :arg
                    {:fn-id (:id map-fn)
                     :name "coll"
                     :type :jsonb
                     :required true}))
```

## Execution Configuration

### Depth and Timeout Limits

```clojure
(exec/create-context
  {:storage storage
   :max-depth 1000      ; Maximum recursion depth (default: 1000)
   :timeout-ms 30000})  ; Execution timeout in ms (default: 30000)
```

### Result Caching

Results are cached by `ref-id` within a single execution context. When multiple args reference the same function via ref-id, the function executes once and the result is reused.

```clojure
;; Both args reference the same expensive-fn - it runs once
(sp/create-entity storage :arg
                  {:fn-id (:id fn-a)
                   :source-id (:id some-arg)
                   :ref-id (:id expensive-fn)})  ; First reference
(sp/create-entity storage :arg
                  {:fn-id (:id fn-b)
                   :source-id (:id some-arg)
                   :ref-id (:id expensive-fn)})  ; Same ref-id = cached result
```

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

To implement a custom storage backend, implement these protocols from `graphden.storage.protocol.core`:

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
   - `validate-no-dependency-cycle!` - Check for circular dependencies via ref-id

5. **ExecutionGraph** - Graph resolution
   - `resolve-execution-graph` - Build execution graph

### Example Implementation Skeleton

```clojure
(ns my.custom-storage
  (:require [graphden.storage.protocol.core :as sp]))

(defrecord CustomStorage [connection metadata]
  sp/Storage
  (initialize [this schema]
    ;; Create tables/collections for fn and arg entities
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
  (:require [graphden.storage.protocol.contract-test :as contract]))

;; Run contract tests with your storage factory
(contract/run-storage-tests create-my-storage)
```

## Graph Data Schema

The 2-entity graph schema includes:

| Entity | Fields | Description |
|--------|--------|-------------|
| `fn` | id, name, parent-id, return-type, impl-hash | Function entity |
| `arg` | id, fn-id, source-id, name, type, required, value, ref-id, is-fn | Argument entity |

### Entity Details

**fn entity:**
- `id` - UUID primary key
- `name` - Unique function name (nullable for local functions)
- `parent-id` - Reference to parent fn (nil for base-fns)
- `return-type` - Return type keyword
- `impl-hash` - SHA-256 hash for base-fn version tracking

**arg entity:**
- `id` - UUID primary key
- `fn-id` - Owner function
- `source-id` - Reference to parent's arg (for inheritance)
- `name` - Argument name
- `type` - Argument type keyword
- `required` - Whether argument is required
- `value` - Literal value (JSONB)
- `ref-id` - Reference to another function
- `is-fn` - HOF flag (when true, ref-id is passed without execution)

### Inheritance Model

Functions inherit args from their parent via `source-id`:

```clojure
;; Base function with args
(let [base-fn (sp/create-entity storage :fn
                                {:name "add" :parent-id nil :return-type :int})
      arg-a (sp/create-entity storage :arg
                              {:fn-id (:id base-fn) :name "a" :type :int :required true})
      arg-b (sp/create-entity storage :arg
                              {:fn-id (:id base-fn) :name "b" :type :int :required true})]

  ;; Composed function inherits and provides values
  (let [composed (sp/create-entity storage :fn
                                   {:name "add-5-and-3" :parent-id (:id base-fn)})]
    ;; Inherit arg-a with value 5
    (sp/create-entity storage :arg
                      {:fn-id (:id composed)
                       :source-id (:id arg-a)  ; Inherits from parent's arg
                       :value 5})
    ;; Inherit arg-b with value 3
    (sp/create-entity storage :arg
                      {:fn-id (:id composed)
                       :source-id (:id arg-b)
                       :value 3})))
```

### Customizing the Schema

You can extend the schema by modifying `graph-data-schema`:

```clojure
(ns my.extended-schema
  (:require [graphden.schema.graph.schema :as graph]
            [graphden.schema.protocol.protocol :as ds]))

(defn build-extended-schema [builder]
  ;; Start with base graph schema
  (let [schema (graph/extend-builder builder)]
    ;; Add custom entities
    (-> schema
        (ds/add-entity :my-entity
                       {:id {:type :uuid :primary-key true}
                        :name {:type :text :nullable false}
                        :data {:type :jsonb :nullable true}}))))
```

## Base Function Version Tracking

When a base function is synced to storage, an `impl-hash` is computed and stored in the `fn` entity. This hash enables detecting when implementations change.

### How impl-hash Works

The hash is computed from:
- `:args` - argument specifications (types, required flags)
- `:return-type` - the function's return type
- `:impl-source` - the original body forms (captured by defbase macro)

```clojure
;; defbase automatically captures impl-source
(defbase my-fn
  {:args {:x :int :y :int}
   :return-type :int}
  (+ x y))  ; This body is stored in :impl-source

;; The generated definition includes:
;; {:args {:x :int :y :int}
;;  :return-type :int
;;  :impl <fn>
;;  :impl-source [(+ x y)]}  ; Original body for hashing
```

### What Changes the Hash

| Change | Hash Changes? |
|--------|---------------|
| Function body changes | Yes |
| Argument type changes | Yes |
| Arguments added/removed | Yes |
| Return type changes | Yes |
| Whitespace/formatting | No |
| Comments | No |
| Map key ordering | No |

### Checking impl-hash

```clojure
(require '[graphden.storage.protocol.core :as sp])

;; Read fn to see impl-hash
(let [base-fn (sp/read-entity storage :fn fn-id)]
  (println "impl-hash:" (:impl-hash base-fn)))
```

## Best Practices

1. **Register base functions at startup** - Before any execution
2. **Use is-fn flag for HOF** - Set `is-fn: true` on args that receive functions
3. **Set reasonable limits** - Adjust max-depth and timeout for your use case
4. **Handle errors gracefully** - Catch and log execution errors
5. **Test with contract tests** - Ensure storage implementations are correct
6. **Monitor impl-hash changes** - Track when base function implementations change
