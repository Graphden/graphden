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


;; Registry index — all published versions, metadata only (omits the
;; heavy `:fns` bundle). `sp/query-entities` decodes the jsonb columns,
;; so the result is plain Clojure data; `:id` / `:published-at` are
;; stringified for clean JSON.
(defbase list-package-versions
  []
  (cr/record-effect! :db)
  (->> (sp/query-entities (request/require-storage ctx) :package-version {})
       (mapv (fn [r]
               {:id (str (:id r))
                :name (:name r)
                :version (:version r)
                :ns-root (:ns-root r)
                :content-hash (:content-hash r)
                :published-at (str (:published-at r))
                :fn-count (count (:fns r))
                :dependencies (:dependencies r)}))))


;; The full published bundle for one (name, version), or nil if absent.
(defbase fetch-package-version
  [pkg-name pkg-version]
  (cr/record-effect! :db)
  (when-let [r (first (sp/query-entities (request/require-storage ctx) :package-version
                                         {:name pkg-name :version pkg-version}))]
    (assoc r :id (str (:id r)) :published-at (str (:published-at r)))))


(def impls
  {:export-namespace export-namespace
   :publish-package publish-package
   :list-package-versions list-package-versions
   :fetch-package-version fetch-package-version})
