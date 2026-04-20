(ns graphden.executor.registry.interface
  "Infrastructure for base function registration and storage sync.

   This component provides:
   - Registration of base-fn impls in the global registry
   - Storage synchronization via `sync-defs-to-storage!`
   - Deterministic UUID generation for idempotent sync

   For defining base-fn implementations themselves, use the `defbase`
   macro in `graphden.executor.defbase`. Base function implementations
   live in packages (`resources/packages/`); see `graphden.packages.loader`
   for the package loading API."
  (:require
    [graphden.executor.registry.core :as core]
    [graphden.packages.loader :as pkg]
    [graphden.storage.protocol.core :as sp]))


;; === Function Registration ===

(defn register-base-fns!
  "Registers base functions from a definitions map. Impls receive the
   raw args map — use `rt/resolve-arg` inside the body.

   Arguments:
   - defs: map of {fn-name -> fn-def}

   Each fn-def should have:
   - :args - map of {arg-name -> type}
   - :return-type - keyword for return type
   - :impl - implementation function (receives args map + ctx)"
  [defs]
  (core/register-base-fns! defs))


;; === Storage Sync ===

(defn fn-uuid
  "Generates deterministic UUID for a base function.
   Uses UUID v5 (name-based) for reproducible IDs."
  [fn-name]
  (core/fn-uuid fn-name))


(defn arg-uuid
  "Generates deterministic UUID for a base function's arg.
   Uses UUID v5 (name-based) for reproducible IDs."
  [fn-name arg-name]
  (core/arg-uuid fn-name arg-name))


(defn sync-defs-to-storage!
  "Syncs function definitions to storage.
   Creates fn and arg entities for each base function.
   Uses deterministic UUIDs so syncing is idempotent.

   Arguments:
   - storage: a storage instance that implements StorageCRUD
   - defs: map of {fn-name -> fn-def} where fn-def has :args, :return-type

   Returns a map with counts:
   {:fns {:created n :updated m}
    :args {:created n :updated m}}"
  ([storage defs]
   (core/sync-defs-to-storage! storage defs {}))
  ([storage defs ns-id-map]
   (core/sync-defs-to-storage! storage defs ns-id-map)))


;; === Storage Initialization Helper ===

(defn initialize-with-base-fns!
  "Initializes a storage with base function definitions from packages.

   Loads default packages (core+web+app), registers base-fns in the
   executor, and syncs schemas to storage. Closes storage on error."
  ([storage]
   (initialize-with-base-fns! storage ["core" "web" "app"]))
  ([storage package-names]
   (try
     (let [packages (pkg/load-packages package-names)
           base-fn-defs (:base-fn-defs packages)]
       (register-base-fns! base-fn-defs)
       (sync-defs-to-storage! storage base-fn-defs)
       storage)
     (catch Exception e
       (sp/close storage)
       (throw e)))))


(defn initialize-all!
  "Initializes storage with multiple sets of base function definitions."
  [storage def-sets]
  (doseq [defs def-sets]
    (register-base-fns! defs)
    (sync-defs-to-storage! storage defs))
  storage)


(defn create-storage-with-base-fns
  "Generic factory that creates storage and initializes with base functions."
  [create-fn & args]
  (-> (apply create-fn args)
      (initialize-with-base-fns!)))
