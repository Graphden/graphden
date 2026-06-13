(ns ^:integration graphden.packages.storage.decode-row-test
  "End-to-end tests for the `:decode-row` storage-as-graph primitive.

   `:decode-row` takes a raw next.jdbc result row (snake_case keys,
   undecoded enum strings, raw PGobjects for jsonb) and returns
   graphden's canonical shape (kebab-case kw keys, enum values as
   keywords, decoded jsonb, etc.). The transformation is identical
   to what `:get-entity` applies internally via the `entities/*`
   helpers — extracting it into a graph primitive was the foundation
   for migrating consumer fn-defs onto direct `:pg-query` +
   `:decode-row` compositions."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(def ^:dynamic *context* nil)
(def ^:dynamic *storage* nil)


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


(deftest decode-row-decodes-service-entity-end-to-end
  (testing ":pg-query → :decode-row recovers the canonical decoded shape"
    ;; The test integrant init runs only `:exec/compiled-registry`, not
    ;; `:exec/service-reconciler` — so package-declared services are
    ;; NOT auto-seeded. Insert one directly so the query under test
    ;; has something to decode. `:fn-id` references the seeded
    ;; `:web-server` fn so the FK validates.
    (let [web-server-fn-id (:id (first (sp/query-entities *storage* :fn
                                                          {:name "web-server"})))]
      (sp/create-entity *storage* :service
                        {:fn-id web-server-fn-id
                         :enabled? true
                         :restart-policy :always}))

    ;; Sync a small consumer chain: pg-query a single :service row,
    ;; then decode-row it. Verify the decoded shape is the
    ;; canonical graphden shape (kebab-case kw keys, enum values as
    ;; keywords, etc.) — the previous version of this test compared
    ;; against the `:list-entities` CRUD path as a control, but that
    ;; base-fn was retired once consumers migrated onto direct
    ;; `:pg-query` + `:decode-row` compositions.
    (fn-composition/sync-fns-to-storage!
      *storage*
      [{:name :_decode-test-raw
        :parent :pg-query
        :args {:hsql {:value {:select [:*]
                              :from :service
                              :limit 1}}}}

       {:name :_decode-test-first
        :parent :first
        :args {:coll :_decode-test-raw}}

       {:name :decode-test-via-graph
        :parent :decode-row
        :args {:row :_decode-test-first
               :entity-type {:value "service"}}}])
    ;; The integrant init triggered a single `cr/rebuild!` BEFORE
    ;; this sync. Drop the compiled registry so the next execute
    ;; rebuilds from storage (now including the just-synced consumer).
    (exec-ctx/invalidate-graph-cache! *context*)

    (let [via-graph (exec/execute *context* (fn-id "decode-test-via-graph") {})]
      (testing "key shape is kebab-case kw (not raw snake_case)"
        (is (contains? via-graph :fn-id)
            ":fn-id (kebab) present, not :fn_id (snake)")
        (is (contains? via-graph :restart-policy))
        (is (not (contains? via-graph :fn_id))
            "raw snake_case key absent — codec normalised"))

      (testing "enum field decoded as keyword (not raw text)"
        (is (keyword? (:restart-policy via-graph))
            ":restart-policy is a keyword, not a string")
        (is (= :always (:restart-policy via-graph))
            "expected enum value present"))

      (testing "bool field decoded as boolean"
        (is (true? (:enabled? via-graph))
            ":enabled? true round-trips as a Clojure bool")))))
