(ns graphden.fn-registry.interface
  "Infrastructure for base function registration and storage sync.

   This component provides the shared infrastructure for defining base functions:
   - `defbase` macro for convenient function definitions with auto-deref
   - Storage synchronization via `sync-defs-to-storage!`
   - Deterministic UUID generation for idempotent sync

   ## Defining Base Functions

   Use the `defbase` macro for convenient definition:

   ```clojure
   (require '[graphden.fn-registry.interface :refer [defbase]])

   (defbase my-add
     {:args {:a :int, :b :int}
      :return-type :int}
     (+ a b))
   ```

   See `graphden.fn-registry.macros` for full documentation.

   Base function implementations (arithmetic, strings, etc.) are in the
   base-functions component which uses this infrastructure."
  (:require
    [graphden.base-functions.interface :as bf]
    [graphden.fn-registry.core :as core]
    [graphden.fn-registry.macros :as macros]
    [graphden.storage-protocol.interface :as sp]))


;; === Function Registration ===

(defn register-base-fns!
  "Registers base functions from a definitions map.

   Arguments:
   - defs: map of {fn-name -> fn-def}

   Each fn-def should have:
   - :args - map of {arg-name -> type}
   - :return-type - keyword for return type
   - :impl - implementation function (receives delays, uses @ to deref)
   - :lazy - (optional) set of arg names NOT to auto-deref

   Use defbase macro to create fn-defs with automatic arg handling."
  [defs]
  (core/register-base-fns! defs))


;; === Storage Sync ===

(defn fn-schema-uuid
  "Generates deterministic UUID for a base function's fn-schema.
   Uses UUID v5 (name-based) for reproducible IDs."
  [fn-name]
  (core/fn-schema-uuid fn-name))


(defn arg-schema-uuid
  "Generates deterministic UUID for a base function's arg-schema.
   Uses UUID v5 (name-based) for reproducible IDs."
  [fn-name arg-name]
  (core/arg-schema-uuid fn-name arg-name))


(defn sync-defs-to-storage!
  "Syncs function definitions to storage.
   Creates fn-schema and arg-schema entries for each function.
   Uses deterministic UUIDs so syncing is idempotent.

   This should be called after storage is initialized with the graph schema.

   Arguments:
   - storage: a storage instance that implements StorageCRUD
   - defs: map of {fn-name -> fn-def} where fn-def has :args, :return-type

   Returns a map with counts:
   {:fn-schemas {:created n :updated m}
    :arg-schemas {:created n :updated m}}"
  [storage defs]
  (core/sync-defs-to-storage! storage defs))


;; === Storage Initialization Helper ===

(defn initialize-with-base-fns!
  "Initializes a storage with base function definitions.

   This is a convenience function that:
   1. Registers all base functions in the executor
   2. Syncs base function schemas to storage
   3. Handles errors by closing storage and re-throwing

   Takes an already-created storage instance (from any backend) and
   prepares it for graph operations with all base functions available.

   Arguments:
   - storage: an initialized storage instance (memory, postgres, datomic, etc.)

   Returns the storage instance on success.
   On error, closes the storage and re-throws the exception.

   Example:
     (-> (gsm/create-storage)
         (registry/initialize-with-base-fns!))"
  [storage]
  (try
    ;; Register base functions in executor
    (register-base-fns! (bf/get-all-defs))
    ;; Sync base function schemas to storage
    (sync-defs-to-storage! storage (bf/get-all-defs))
    storage
    (catch Exception e
      (sp/close storage)
      (throw e))))


(defn initialize-all!
  "Initializes storage with multiple sets of base function definitions.

   This function:
   1. Registers all base functions in the executor (for runtime lookup)
   2. Syncs fn-schema and arg-schema to storage (for graph references)

   Arguments:
   - storage: an initialized storage instance
   - def-sets: sequence of base-fn definition maps (each is {fn-name -> fn-def})

   Returns the storage instance.

   Example:
     (initialize-all! storage
       [(bf/get-all-defs)           ; arithmetic, strings
        web-server/all-defs         ; http-kit, reitit
        handlers/all-defs])         ; hello, health, router"
  [storage def-sets]
  (doseq [defs def-sets]
    (register-base-fns! defs)
    (sync-defs-to-storage! storage defs))
  storage)


(defn create-storage-with-base-fns
  "Generic factory that creates storage and initializes with base functions.

   This is a convenience function for creating complete graphden environments.
   It wraps any storage creation function with base function initialization.

   Arguments:
   - create-fn: a function that creates a storage instance (e.g., gsp/create-storage)
   - args: arguments to pass to create-fn

   Returns a storage instance with all base functions registered and synced.

   Example:
     (require '[graphden.graph-storage-postgres.interface :as gsp])
     (create-storage-with-base-fns gsp/create-storage {:jdbc-url \"...\"})"
  [create-fn & args]
  (-> (apply create-fn args)
      (initialize-with-base-fns!)))


;; === Macro Configuration ===

(def ^:dynamic *custom-binding-forms*
  "Set of additional binding forms to recognize in defbase macro.
   Use this to register custom binding macros from libraries (e.g., core.async).
   See graphden.fn-registry.macros/*custom-binding-forms* for details."
  macros/*custom-binding-forms*)


;; === Macro for Defining Base Functions ===

(defmacro defbase
  "Defines a base function with automatic argument handling.

   All arguments are automatically dereferenced (from delay) EXCEPT
   those listed in `:lazy`. Use `@arg` to manually deref lazy args.

   Arguments:
   - name: Symbol for the function definition
   - docstring: Optional documentation string
   - opts: Map with :args, :return-type, and optional :lazy set
   - body: Function body expressions

   Example:
   ```clojure
   ;; Simple function - args auto-deref'd
   (defbase add
     {:args {:a :int, :b :int}
      :return-type :int}
     (+ a b))

   ;; Lazy args for conditional evaluation
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

   See `graphden.fn-registry.macros` for full documentation."
  [fn-name & args]
  `(macros/defbase ~fn-name ~@args))
