(ns graphden.fn-registry.interface
  "Infrastructure for base function registration and storage sync.

   This component provides the shared infrastructure for defining base functions:
   - Automatic argument forcing via wrap-base-fn
   - Storage synchronization via sync-defs-to-storage!
   - Deterministic UUID generation for idempotent sync

   Base function implementations (arithmetic, strings, etc.) are in the
   base-functions component which uses this infrastructure."
  (:require
    [graphden.base-functions.interface :as bf]
    [graphden.fn-registry.core :as core]
    [graphden.storage-protocol.interface :as sp]))


;; === Function Definition ===

(defn wrap-base-fn
  "Wraps a base function implementation to handle argument forcing.

   Takes a function definition map with:
   - :args - map of {arg-name -> type}
   - :impl - the implementation function (receives processed args and ctx)
   - :lazy-args - (optional) set of arg names that receive thunks

   Returns a wrapped function suitable for registration.

   Behavior:
   - Regular args: pre-forced, impl receives plain values
   - :fn type args: force-value returns fn-id, impl receives fn-id
   - :lazy-args: impl receives thunk, must call force-value manually"
  [fn-def]
  (core/wrap-base-fn fn-def))


(defn register-base-fns!
  "Registers base functions from a definitions map.

   Arguments:
   - defs: map of {fn-name -> fn-def}

   Each fn-def should have:
   - :args - map of {arg-name -> type}
   - :return-type - keyword for return type
   - :impl - implementation function
   - :lazy-args - (optional) set of lazy arg names"
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
