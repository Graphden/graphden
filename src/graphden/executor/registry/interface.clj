(ns graphden.executor.registry.interface
  "Public façade for base function registration and storage sync.

   Defining base-fn implementations themselves uses the `defbase` macro
   in `graphden.executor.defbase`. Implementations live in packages
   (`resources/packages/`); see `graphden.packages.loader`."
  (:require
    [graphden.executor.registry.core :as core]
    [graphden.packages.loader :as pkg]
    [graphden.storage.protocol.core :as sp]))


;; === Function Registration ===

(defn register-base-fns!
  "Registers base functions from a `{fn-name → fn-def}` map. Type-rows
   (no `:impl` key) get a synthesised impl for their role."
  [defs]
  (core/register-base-fns! defs))


;; === Storage Sync ===

(defn fn-uuid
  "Deterministic UUID for a globally-named (namespace-less) fn. Used
   by tests that pre-built UUIDs match production records."
  [fn-name]
  (core/fn-uuid fn-name))


(defn sync-defs-to-storage!
  "Sync `{fn-name → fn-def}` to storage via the records-parser
   pipeline. Returns the fn-name→id map produced by the underlying
   `write-records!`."
  ([storage defs]
   (core/sync-defs-to-storage! storage defs {} {}))
  ([storage defs ns-id-map]
   (core/sync-defs-to-storage! storage defs ns-id-map {}))
  ([storage defs ns-id-map extra-name->id]
   (core/sync-defs-to-storage! storage defs ns-id-map extra-name->id)))


(defn sync-primitives!
  "Pre-seed the 14 primitive fn-rows. Idempotent. Should run once at
   storage init before any other sync."
  [storage]
  (core/sync-primitives! storage))


;; === Storage Initialization Helper ===

(defn initialize-with-base-fns!
  "Initialises a storage with base-fns from the default packages.
   Loads core+web+app, registers base-fns in the executor, syncs
   primitives + base-fn rows to storage."
  ([storage]
   (initialize-with-base-fns! storage ["core" "web" "app"]))
  ([storage package-names]
   (try
     (let [packages (pkg/load-packages package-names)
           base-fn-defs (:base-fn-defs packages)]
       (sync-primitives! storage)
       (register-base-fns! base-fn-defs)
       (sync-defs-to-storage! storage base-fn-defs)
       storage)
     (catch Exception e
       (sp/close storage)
       (throw e)))))


(defn initialize-all!
  "Initialises storage with multiple sets of base-fn definitions."
  [storage def-sets]
  (sync-primitives! storage)
  (doseq [defs def-sets]
    (register-base-fns! defs)
    (sync-defs-to-storage! storage defs))
  storage)
