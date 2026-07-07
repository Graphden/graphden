(ns ^:integration ^:serial graphden.packages.storage.resolve-versioned-rows-test
  "End-to-end tests for `:resolve-versioned-rows` — pure graph
   composition equivalent of `versioning.storage.resolution/
   resolve-all-entities`. Verifies the graph output matches the
   Clojure source-of-truth for each versioned entity type, then
   shows a per-site call shape.

   `^:serial`: this NS compares a graph-execute against a direct
   Clojure resolve of the SAME data — an exact-equivalence check that
   is intolerant of any transient hiccup. Under the parallel
   integration pool on a resource-constrained host it intermittently
   dropped one row (a partial read while the shared Testcontainers PG
   was under connection/memory pressure — the same pressure that
   surfaces as `57P01 terminating connection` elsewhere). The logic is
   isolation-clean (proven across many focused runs, sw29–sw32), so
   running it in the sequential pre-pass — off the contention window —
   makes it deterministic without masking a real bug."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as res]))


(def ^:dynamic *context* nil)
(def ^:dynamic *storage* nil)


;; Fixture shape: the heavy package bootstrap is shared across the JVM
;; (golden DB + per-NS clone — see test-infra.shared-bootstrap). This
;; NS pays the ~100 ms clone instead of ~14 s `ig/init [:exec/
;; compiled-registry]`. With-clean-registry isolates the parallel
;; kaocha pool's base-fn impl atom from sibling NSes.
(use-fixtures :once
  (setup/create-container-fixture)
  (fn [t]
    (exec/with-clean-registry
      #(let [graph (setup/bootstrap-crud-graph-from-golden!)]
         (try
           (binding [*context* (:ctx graph)
                     *storage* (:storage graph)]
             (t))
           (finally (sp/close (:storage graph))))))))


(defn- fn-id
  [nm]
  (:id (first (sp/query-entities *storage* :fn {:name nm}))))


(defn- main-branch-id
  []
  (:id (first (sp/query-entities *storage* :branch {:name "main"}))))


(defn- resolve-call-shape-fns
  "Build the 8 fn-def records that materialise one per-site
   call shape: load identities + version rows + chain, then call
   `:resolve-versioned-rows`. `cfg` keys:
     :public-name         — name of the public fn-def (synced)
     :entity-type         — string, e.g. \"fn\"
     :version-table       — keyword, e.g. :fn-version
     :version-id-field    — keyword, e.g. :fn-id
     :version-data-fields — vec of kw"
  [{:keys [public-name entity-type version-table version-id-field version-data-fields]}]
  (let [chain-name      (keyword (str "_" (name public-name) "-chain"))
        ids-name        (keyword (str "_" (name public-name) "-identities"))
        vers-raw-name   (keyword (str "_" (name public-name) "-versions-raw"))
        vers-hsql-name  (keyword (str "_" (name public-name) "-versions-hsql"))
        vers-where-name (keyword (str "_" (name public-name) "-versions-where"))
        vers-decode     (keyword (str "_" (name public-name) "-versions-decode"))
        vers-name       (keyword (str "_" (name public-name) "-versions"))]
    [{:name public-name
      :parent :resolve-versioned-rows
      :args {:identities ids-name
             :version-rows vers-name
             :branch-chain chain-name
             :version-id-field {:value version-id-field}
             :version-data-fields {:value version-data-fields}}}

     {:name chain-name
      :parent :branch-chain
      :args {:branch-id :current-branch-id}}

     {:name ids-name
      :parent :storage-query-identities
      :args {:entity-type {:value entity-type}
             :where {:value {}}}}

     {:name vers-name
      :parent :map
      :args {:func vers-decode
             :coll vers-raw-name}}

     {:name vers-raw-name
      :parent :pg-query
      :args {:hsql vers-hsql-name}}

     {:name vers-hsql-name
      :parent :assoc
      :args {:map {:value {:select [:*] :from version-table}}
             :key {:value :where}
             :value vers-where-name}}

     {:name vers-where-name
      :parent :vec
      :args {:coll [{:value :in} {:value :branch-id} chain-name]}}

     {:name vers-decode
      :parent :decode-row
      :args {:row {:as :item}
             :entity-type {:value (name version-table)}}}]))


(def ^:private call-shapes
  "Per-entity-type call-shape configs for the four versioned entities.
   Each entry produces 8 fn-def records via `resolve-call-shape-fns`."
  [{:public-name :test-resolve-fn-rows
    :entity-name :fn
    :entity-type "fn"
    :version-table :fn-version
    :version-id-field :fn-id
    :version-data-fields [:name :description :constraint
                          :base-fn-id :element-fn-id :return-type-fn-id
                          :anonymous-hash :expects-effects]}
   {:public-name :test-resolve-fn-slot-rows
    :entity-name :fn-slot
    :entity-type "fn-slot"
    :version-table :fn-slot-version
    :version-id-field :fn-slot-id
    :version-data-fields [:fn-id :slot-id :position]}
   {:public-name :test-resolve-binding-rows
    :entity-name :binding
    :entity-type "binding"
    :version-table :binding-version
    :version-id-field :binding-id
    :version-data-fields [:fn-id :slot-id :value :value-present :ref-fn-id
                          :override-kind :type-override-fn-id
                          :description :list-append :list-closed]}
   {:public-name :test-resolve-bli-rows
    :entity-name :binding-list-item
    :entity-type "binding-list-item"
    :version-table :binding-list-item-version
    :version-id-field :item-id
    :version-data-fields [:binding-id :position :value :ref-fn-id :literal]}])


(deftest resolve-versioned-rows-matches-clojure-end-to-end
  ;; All four entity types share the SAME assertion shape ("graph
  ;; output equals `resolve-all-entities`"). Consolidated into one
  ;; deftest so the per-site fn-def syncs (32 rows across the 4
  ;; shapes) land in ONE batch + ONE graph-cache invalidate, and
  ;; the first `exec/execute` pays the registry rebuild once for
  ;; the entire batch instead of once per entity type.
  (let [all-fns (vec (mapcat resolve-call-shape-fns call-shapes))]
    (fn-composition/sync-fns-to-storage! *storage* all-fns)
    (exec-ctx/invalidate-graph-cache! *context*)
    (doseq [{:keys [public-name entity-name]} call-shapes]
      (testing (str (name entity-name)
                    " — graph output matches Clojure resolve-all-entities")
        (let [via-graph   (exec/execute *context* (fn-id (name public-name)) {})
              via-clojure (res/resolve-all-entities
                            (vs/unwrap *storage*) entity-name
                            (main-branch-id) {})]
          (is (= (count via-clojure) (count via-graph))
              "row count matches between graph and Clojure")
          (is (= (set (map :id via-clojure)) (set (map :id via-graph)))
              ":id sets match"))))))
