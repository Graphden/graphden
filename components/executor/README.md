# executor

Function graph executor for graphden. Executes functions stored in the graph by resolving their dependencies, building lazy thunks, and calling registered base functions.

## Concepts

### Execution Model

The executor uses **lazy evaluation** via thunks. When a function is executed:

1. The complete execution graph is fetched from storage (all referenced functions, their schemas, and arg-values)
2. For each argument, a thunk is created representing a deferred computation
3. The base function is called with thunks as arguments
4. The base function forces thunks as needed, enabling:
   - Conditional evaluation (e.g., `if` only evaluates the chosen branch)
   - Higher-order functions (e.g., `map` repeatedly forces a function thunk with different args)

### Thunk Types

| Type | Description |
|------|-------------|
| `LiteralThunk` | Wraps a literal value, `force-value` returns it directly |
| `FnRefThunk` | References another function, `force-value` executes it recursively |
| `LazyFnThunk` | For `:fn` type args, `force-value` returns the fn-id (not the result) |

### Base Functions

Base functions are the primitive operations that thunks eventually execute. They're registered globally:

```clojure
(require '[graphden.executor.interface :as exec])

;; Register an addition function
(exec/register-base-fn! :add
  (fn [{:keys [a b]} ctx]
    (+ (exec/force-value a ctx)
       (exec/force-value b ctx))))

;; Register a conditional
(exec/register-base-fn! :if
  (fn [{:keys [condition then else]} ctx]
    (if (exec/force-value condition ctx)
      (exec/force-value then ctx)
      (exec/force-value else ctx))))
```

## Usage

```clojure
(require '[graphden.executor.interface :as exec])
(require '[graphden.storage-protocol.interface :as sp])

;; Create execution context (uses default global registry)
(def ctx (exec/create-context {:storage my-storage
                               :max-depth 1000    ; optional
                               :timeout-ms 30000})) ; optional

;; Create context with custom base-fns (for isolation/testing)
(def ctx (exec/create-context {:storage my-storage
                               :base-fns {:add my-add-fn
                                          :if my-if-fn}}))

;; Execute a function by its UUID
(def result (exec/execute ctx fn-id {}))

;; Execute with runtime arguments (override stored arg-values)
(def result (exec/execute ctx fn-id {arg-schema-id 42}))
```

## Protection Mechanisms

### Recursion Depth

Default: 1000 levels. Prevents stack overflow from infinite recursion.

```clojure
;; Throws :execution-error/max-depth-exceeded
(exec/create-context {:storage s :max-depth 100})
```

### Execution Timeout

Default: 30 seconds. Prevents runaway computations.

```clojure
;; Throws :execution-error/timeout
(exec/create-context {:storage s :timeout-ms 5000})
```

## Error Types

| Error Type | Description |
|------------|-------------|
| `:execution-error/invalid-context` | Storage not provided |
| `:execution-error/fn-not-found` | Function not in execution graph |
| `:execution-error/base-fn-not-found` | Base function not registered |
| `:execution-error/missing-required-arg` | Required argument has no value |
| `:execution-error/type-mismatch` | Provided arg doesn't match expected type |
| `:execution-error/max-depth-exceeded` | Recursion limit reached |
| `:execution-error/timeout` | Execution time limit exceeded |

## API

### `create-context`

```clojure
(create-context {:storage storage
                 :max-depth 1000      ; optional, default 1000
                 :timeout-ms 30000})  ; optional, default 30000
```

### `execute`

```clojure
(execute context fn-id args) ;; => result value
```

### `register-base-fn!`

```clojure
(register-base-fn! :fn-name (fn [args ctx] ...))
```

### `get-base-fn`

```clojure
(get-base-fn :fn-name) ;; => fn or nil
```

### `force-value`

```clojure
(force-value thunk context) ;; => forced value
```

## Type Validation

When providing arguments via the `args` map, the executor validates types:

| Schema Type | Expected Clojure Type |
|-------------|----------------------|
| `:int` | `int?` |
| `:bool` | `boolean?` |
| `:text` | `string?` |
| `:numeric` | `number?` |
| `:fn` | `uuid?` (function reference) |
| `:ref` | `uuid?` |
| `:uuid` | `uuid?` |
| `:jsonb` | `map?` or `vector?` |
| `:bytes` | `bytes?` |
| `:timestamptz` | `java.time.Instant` or `java.util.Date` |
| `:enum` | `keyword?` |
| `:union` | Any value (no strict validation) |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         execute                              │
│  1. Resolve execution graph from storage                     │
│  2. Check limits (depth, timeout)                            │
│  3. Build thunks for all arguments                           │
│  4. Call base function with thunks                           │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      Base Function                           │
│  - Receives thunks as arguments                              │
│  - Calls force-value when value is needed                    │
│  - Returns result                                            │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      force-value                             │
│  - LiteralThunk: return value                                │
│  - FnRefThunk: recursive execute                             │
│  - LazyFnThunk: return fn-id                                 │
└─────────────────────────────────────────────────────────────┘
```

## Dependencies

- `storage-protocol` - Storage protocol for graph resolution

## See Also

- [ERROR_CODES.md](../../docs/ERROR_CODES.md) - All error types
- [CONSTRAINTS.md](../../docs/CONSTRAINTS.md) - Graph constraints
