(ns graphden.input.storage-schema-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.request :as request]
    [graphden.input.access :as access]
    [graphden.input.rows :as rows]
    [graphden.schema.graph.schema :as graph]
    [graphden.schema.malli.core :as malli]
    [graphden.schema.protocol.protocol :as schema]
    [graphden.schema.versioned.schema :as versioned]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.input :as input-storage]))


(deftest mirrors-and-identity-fields-test
  (let [s (-> (malli/create-builder) graph/extend-builder versioned/extend-builder schema/build)
        base (schema/entity-fields s :input-binding)
        mirror (schema/entity-fields s :input-binding-version)]
    (is (= :ref (get-in base [:fn-id :type])))
    (is (= :uuid (get-in mirror [:fn-id :type])))
    (is (not (contains? mirror :org-id)))
    (is (= (disj (set (keys base)) :org-id)
           (versioned/version-data-fields :input-binding)))
    (is (contains? (versioned/version-data-fields :fn) :input-type-fn-id))
    (is (true? (get-in mirror [:fn-id :indexed?])))))


(deftest public-writers-stay-v1-test
  (doseq [entity [:input-binding :input-binding-version]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"offline migration"
          (sp/standard-crud-normalize-data entity {})))
    (is (nil? (request/entity-type-from-string (name entity) #{entity}))))
  (is (thrown? clojure.lang.ExceptionInfo
        (sp/standard-crud-normalize-data :fn {:input-type-fn-id (random-uuid)})))
  (is (= {:input-type-fn-id nil :name "v1"}
         (sp/standard-crud-normalize-data :fn-version {:input-type-fn-id nil :name "v1"})))
  (binding [access/*offline?* true]
    (is (= {} (sp/standard-crud-normalize-data :input-binding {})))))


(deftest persisted-paths-and-occupancy-test
  (let [owner (random-uuid) field (random-uuid) child (random-uuid)
        row {:id (random-uuid) :fn-id owner :scope-path []
             :target-path [field] :expression-kind "value" :value-present true :value nil}]
    (is (= [field] (rows/uuid-path [(str field)])))
    (is (thrown? clojure.lang.ExceptionInfo (rows/uuid-path ["not-an-id"])))
    (is (= {} (rows/state [(assoc row :expression-kind nil :value-present false)])))
    (testing "Whole versus descendant is rejected across distinct persisted IDs"
      (is (thrown? clojure.lang.ExceptionInfo
            (rows/state [row (assoc row :id (random-uuid) :target-path [field child])]))))
    (testing "A repeated shared origin deduplicates"
      (is (= 1 (count (rows/state [row row])))))
    (is (nil? (get-in (rows/state [row]) [[[owner []] [field]] :expression :value])))))


(deftest root-binding-cannot-reference-a-foreign-input-contract-test
  (let [owner (random-uuid) descriptor (random-uuid)
        view {:fns {owner {:id owner :org-id "mine" :input-type-fn-id descriptor}
                    descriptor {:id descriptor :org-id "other"}}
              :fields {} :members {} :bindings {}}
        row {:id (random-uuid) :fn-id owner :org-id "mine" :scope-path [] :target-path []
             :expression-kind "value" :value-present true :value {}}]
    (is (thrown? clojure.lang.ExceptionInfo (input-storage/validate-row! view row)))
    (is (= row (input-storage/validate-row! (assoc-in view [:fns descriptor :org-id] nil) row)))))
