(ns graphden.packages.app.registry.impls
  "Implementations for app/registry base functions. Thin primitive over
   `graphden.packages.export` — the multi-step publish/extract flow is
   graph composition (fn-defs) over this + the CRUD base-fns."
  (:require
    [cheshire.core :as json]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.composition.interface :as composition]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.packages.export :as export]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]))


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


;; Install a published version into the target graph. Transactional with
;; an install precondition (packages-quality §3): the bundle's declared
;; dependencies must ALL already exist, else reject without writing. The
;; sync itself (namespaces + fn-defs) is a Clojure operation not
;; expressible as graph composition, so the whole flow is one base-fn.
;;
;; Branch staging is FREE: `ctx`'s storage is already scoped to the
;; request's branch (the branch-router), so installing through
;; `X-Graphden-Branch: feature-x` writes the rows onto that branch — test
;; there, then merge (PLATFORM_PLAN §2.4).
;;
;; After the sync writes the fn rows, `invalidate-graph-cache!` drops
;; the compiled registry (and refreshes type-aliases) so the installed
;; fns are executable immediately — no pod restart. Full clear (1-arity)
;; rather than a delta: install is a rare admin op writing a whole
;; bundle, and we don't thread the synced fn-ids back out.
(defbase install-package
  [pkg-name pkg-version]
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)
        row (first (sp/query-entities storage :package-version
                                      {:name pkg-name :version pkg-version}))]
    (if (nil? row)
      {:ok false :reason "not-found" :name pkg-name :version pkg-version}
      (let [fns (:fns row)
            dep-names (mapv name (:dependencies row))
            present (into #{}
                          (map :name)
                          (when (seq dep-names)
                            (sp/query-entities storage :fn {:name (vec (distinct dep-names))})))
            missing (vec (distinct (remove present dep-names)))]
        (if (seq missing)
          {:ok false :reason "missing-dependencies" :missing (mapv keyword missing)}
          (let [ns-id-map (loader/sync-namespaces! storage (into #{} (keep :namespace) fns))]
            (composition/sync-fns-to-storage! storage fns ns-id-map)
            (exec-ctx/invalidate-graph-cache! ctx)
            {:ok true
             :name pkg-name
             :version pkg-version
             :installed (count fns)}))))))


;; ---------------------------------------------------------------------------
;; Package pins — per-branch desired-state "this branch uses package P at V".
;; The pin drives update/rollback (repoint the row) and the editor's installed
;; list. Reference-install (PACKAGE_DISTRIBUTION §4.3) writes a pin instead of
;; copying package rows; these are the pin-lifecycle primitives it builds on.
;; ---------------------------------------------------------------------------

;; Upsert the single pin for `(current-branch, pkg-name)`: query-then-
;; update-or-insert. One-pin-per-(branch,package) is the invariant (mirrors
;; app-side uniqueness of :package-version), so the check+write is one atomic
;; base-fn (packages-quality §3), not spread across graph nodes. Branch comes
;; from the request-scoped VersionedStorage, so pinning through
;; `X-Graphden-Branch: feature-x` records the pin on that branch (staging).
(defbase set-package-pin
  [pkg-name pkg-version]
  (cr/record-effect! :db)
  (cr/record-effect! :time)
  (let [storage (request/require-storage ctx)
        branch-id (vs/current-branch-id storage)
        existing (first (sp/query-entities storage :package-install
                                           {:branch-id branch-id :package-name pkg-name}))
        installed-at (java.time.Instant/now)]
    (if existing
      (sp/update-entity storage :package-install (:id existing)
                        {:version pkg-version :installed-at installed-at})
      (sp/create-entity storage :package-install
                        {:branch-id branch-id
                         :package-name pkg-name
                         :version pkg-version
                         :installed-at installed-at}))
    {:ok true
     :package-name pkg-name
     :version pkg-version
     :branch-id (str branch-id)}))


;; The current branch's pins — what's installed here. `sp/query-entities`
;; decodes the row; ids/timestamps stringified for clean JSON.
(defbase list-installed-packages
  []
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)
        branch-id (vs/current-branch-id storage)]
    (->> (sp/query-entities storage :package-install {:branch-id branch-id})
         (mapv (fn [r]
                 {:package-name (:package-name r)
                  :version (:version r)
                  :branch-id (str (:branch-id r))
                  :installed-at (str (:installed-at r))})))))


;; Drop the pin for `(current-branch, pkg-name)` — uninstall. Returns a tagged
;; result; `:removed false` when nothing was pinned (idempotent).
(defbase remove-package-pin
  [pkg-name]
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)
        branch-id (vs/current-branch-id storage)
        existing (first (sp/query-entities storage :package-install
                                           {:branch-id branch-id :package-name pkg-name}))]
    (when existing
      (sp/delete-entity storage :package-install (:id existing)))
    {:ok true :package-name pkg-name :removed (some? existing)}))


(def impls
  {:export-namespace export-namespace
   :publish-package publish-package
   :list-package-versions list-package-versions
   :fetch-package-version fetch-package-version
   :install-package install-package
   :set-package-pin set-package-pin
   :list-installed-packages list-installed-packages
   :remove-package-pin remove-package-pin})
