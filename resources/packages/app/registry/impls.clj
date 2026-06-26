(ns graphden.packages.app.registry.impls
  "Implementations for app/registry base functions. Thin primitive over
   `graphden.packages.export` — the multi-step publish/extract flow is
   graph composition (fn-defs) over this + the CRUD base-fns."
  (:require
    [cheshire.core :as json]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.packages.export :as export]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]))


(defbase export-namespace
  [root]
  (cr/record-effect! :db)
  (export/export-namespace (request/require-storage ctx) root))


;; Atomic publish: reject if `(pkg-name, pkg-version)` already exists,
;; else hash + insert. Invariant-bearing (immutability) algorithm — a
;; legitimate base-fn (packages-quality §3) and race-safer than spreading
;; check-then-insert across graph nodes. The app-level existence check is
;; the Phase-1 guard; a DB-level UNIQUE(name, version) is the hardening
;; follow-up noted in the schema. Returns a tagged `{:ok …}` result so the
;; route serialises success and conflict the same way (no throw/`:try`).
(defbase publish-package
  [pkg-name pkg-version bundle]
  (cr/record-effect! :db)
  (cr/record-effect! :time)
  (let [storage (request/require-storage ctx)
        existing (sp/query-entities storage :package-version
                                    {:name pkg-name :version pkg-version})]
    (if (seq existing)
      {:ok false :reason "version-exists" :name pkg-name :version pkg-version}
      (let [fns (:fns bundle)
            content-hash (ids/digest-hex "SHA-256" (json/generate-string fns))
            row (sp/create-entity storage :package-version
                                  {:name pkg-name
                                   :version pkg-version
                                   :ns-root (:namespace bundle)
                                   :fns fns
                                   :dependencies (:dependencies bundle)
                                   :content-hash content-hash
                                   :published-at (java.time.Instant/now)})]
        {:ok true
         :id (str (:id row))
         :name pkg-name
         :version pkg-version
         :content-hash content-hash
         :fn-count (count fns)
         :dependencies (:dependencies bundle)}))))


(def impls
  {:export-namespace export-namespace
   :publish-package publish-package})
