# fn-registry

Infrastructure for base function registration and storage synchronization.

## Features

- `defbase` macro for convenient function definitions with automatic argument handling
- `wrap-base-fn` for manual argument forcing
- `sync-defs-to-storage!` for syncing function schemas to storage
- Deterministic UUID generation for idempotent sync

## Defining Base Functions

### Using `defbase` macro (recommended)

```clojure
(require '[graphden.fn-registry.interface :refer [defbase]])

;; Simple function - all args auto-deref'd
(defbase add
  {:args {:a :int, :b :int}
   :return-type :int}
  (+ a b))

;; Lazy args - use @ to deref when needed
(defbase my-if
  {:args {:cond :bool, :then :any, :else :any}
   :lazy #{:then :else}
   :return-type :any}
  (if cond @then @else))

;; HOF - :fn args become callables
(defbase my-map
  {:args {:f :fn, :coll :jsonb}
   :return-type :jsonb}
  (mapv (fn [item] (f {:item item})) coll))
```

### Argument Behavior

| Arg Type | Behavior | In Body |
|----------|----------|---------|
| `:int`, `:text`, `:bool`, etc. | Auto-deref | Use directly: `(+ a b)` |
| `:fn` | Auto-deref | Callable: `(f {:item x})` |
| Listed in `:lazy` | NO auto-deref | Manual deref: `@then` |

### Manual Definition

For full control, define without the macro:

```clojure
(def my-fn
  {:args {:a :int, :b :int}
   :return-type :int
   :impl (fn [{:keys [a b]} ctx]
           (+ @a @b))})  ; manual deref
```

See `graphden.fn-registry.macros` for complete documentation.

## Storage Sync

```clojure
(require '[graphden.fn-registry.interface :as registry])

;; Sync all base functions to storage
(registry/sync-defs-to-storage! storage (bf/get-all-defs))

;; Or use the convenience function
(registry/initialize-with-base-fns! storage)
```

## Dependencies

- `executor` - For base function execution
- `base-functions` - Function definitions
- `storage-protocol` - Storage abstraction
