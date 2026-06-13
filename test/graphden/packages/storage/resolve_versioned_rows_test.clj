(ns ^:integration graphden.packages.storage.resolve-versioned-rows-test
  "End-to-end tests for `:resolve-versioned-rows` — pure graph
   composition equivalent of `versioning.storage.resolution/
   resolve-all-entities`. Verifies the graph output matches the
   Clojure source-of-truth for each versioned entity type, then
   shows a per-site call shape."
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


(defn- sync-resolve-call-shape!
  "Wire up the standard per-site shape: load identities + version
   rows + chain, then call `:resolve-versioned-rows`. `cfg` keys:
     :public-name        — name of the public fn-def (synced)
     :entity-type        — string, e.g. \"fn\"
     :version-table      — keyword, e.g. :fn-version
     :version-id-field   — keyword, e.g. :fn-id
     :version-data-fields — vec of kw"
  [{:keys [public-name entity-type version-table version-id-field version-data-fields]}]
  (let [chain-name      (keyword (str "_" (name public-name) "-chain"))
        ids-name        (keyword (str "_" (name public-name) "-identities"))
        vers-raw-name   (keyword (str "_" (name public-name) "-versions-raw"))
        vers-hsql-name  (keyword (str "_" (name public-name) "-versions-hsql"))
        vers-where-name (keyword (str "_" (name public-name) "-versions-where"))
        vers-decode     (keyword (str "_" (name public-name) "-versions-decode"))
        vers-name       (keyword (str "_" (name public-name) "-versions"))]
    (fn-composition/sync-fns-to-storage!
      *storage*
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
               :entity-type {:value (name version-table)}}}])))


(deftest resolve-versioned-rows-matches-fn
  (testing ":fn — graph output matches Clojure resolve-all-entities"
    (sync-resolve-call-shape!
      {:public-name :test-resolve-fn-rows
       :entity-type "fn"
       :version-table :fn-version
       :version-id-field :fn-id
       :version-data-fields [:name :impl-hash :description :constraint
                             :base-fn-id :element-fn-id :return-type-fn-id
                             :anonymous-hash :expects-effects]})
    (exec-ctx/invalidate-graph-cache! *context*)
    (let [via-graph   (exec/execute *context* (fn-id "test-resolve-fn-rows") {})
          via-clojure (res/resolve-all-entities (vs/unwrap *storage*) :fn
                                                (main-branch-id) {})]
      (is (= (count via-clojure) (count via-graph))
          "row count matches between graph and Clojure")
      (is (= (set (map :id via-clojure)) (set (map :id via-graph)))
          ":id sets match"))))


(deftest resolve-versioned-rows-matches-fn-slot
  (testing ":fn-slot — graph output matches Clojure resolve-all-entities"
    (sync-resolve-call-shape!
      {:public-name :test-resolve-fn-slot-rows
       :entity-type "fn-slot"
       :version-table :fn-slot-version
       :version-id-field :fn-slot-id
       :version-data-fields [:fn-id :slot-id :position]})
    (exec-ctx/invalidate-graph-cache! *context*)
    (let [via-graph   (exec/execute *context* (fn-id "test-resolve-fn-slot-rows") {})
          via-clojure (res/resolve-all-entities (vs/unwrap *storage*) :fn-slot
                                                (main-branch-id) {})]
      (is (= (count via-clojure) (count via-graph)))
      (is (= (set (map :id via-clojure)) (set (map :id via-graph)))))))


(deftest resolve-versioned-rows-matches-binding
  (testing ":binding — graph output matches Clojure resolve-all-entities"
    (sync-resolve-call-shape!
      {:public-name :test-resolve-binding-rows
       :entity-type "binding"
       :version-table :binding-version
       :version-id-field :binding-id
       :version-data-fields [:fn-id :slot-id :value :ref-fn-id
                             :override-kind :type-override-fn-id
                             :description :list-append :list-closed]})
    (exec-ctx/invalidate-graph-cache! *context*)
    (let [via-graph   (exec/execute *context* (fn-id "test-resolve-binding-rows") {})
          via-clojure (res/resolve-all-entities (vs/unwrap *storage*) :binding
                                                (main-branch-id) {})]
      (is (= (count via-clojure) (count via-graph)))
      (is (= (set (map :id via-clojure)) (set (map :id via-graph)))))))


(deftest resolve-versioned-rows-matches-binding-list-item
  (testing ":binding-list-item — graph output matches Clojure resolve-all-entities"
    (sync-resolve-call-shape!
      {:public-name :test-resolve-bli-rows
       :entity-type "binding-list-item"
       :version-table :binding-list-item-version
       :version-id-field :item-id
       :version-data-fields [:binding-id :position :value :ref-fn-id :literal]})
    (exec-ctx/invalidate-graph-cache! *context*)
    (let [via-graph   (exec/execute *context* (fn-id "test-resolve-bli-rows") {})
          via-clojure (res/resolve-all-entities (vs/unwrap *storage*) :binding-list-item
                                                (main-branch-id) {})]
      (is (= (count via-clojure) (count via-graph)))
      (is (= (set (map :id via-clojure)) (set (map :id via-graph)))))))
