(ns graphden.postgres-storage.crud-test
  "Unit tests for PostgreSQL CRUD functions that don't require a database."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.postgres-storage.crud :as crud])
  (:import
    (org.postgresql.util
      PGobject)))


;; === Helper to create PGobject ===

(defn- make-pgobject
  "Creates a PGobject with given type and value."
  [pg-type pg-value]
  (doto (PGobject.)
    (PGobject/.setType pg-type)
    (PGobject/.setValue pg-value)))


;; === Row/Entity Conversion Tests ===
;; These test the codec module which is used by crud functions

(deftest row->entity-test
  (testing "converts snake_case keys to kebab-case"
    (let [row {:user_name "john" :created_at "2024-01-01"}]
      (is (= {:user-name "john" :created-at "2024-01-01"}
             (#'crud/row->entity row)))))

  (testing "handles single-word keys"
    (let [row {:id 1 :name "test"}]
      (is (= {:id 1 :name "test"}
             (#'crud/row->entity row)))))

  (testing "parses PGobject values"
    (let [pg (make-pgobject "jsonb" "{\"a\": 1}")
          row {:data pg :name "test"}]
      (is (= {:data {:a 1} :name "test"}
             (#'crud/row->entity row)))))

  (testing "returns nil for nil input"
    (is (nil? (#'crud/row->entity nil)))))


(deftest entity->row-test
  (testing "converts kebab-case keys to snake_case"
    (let [entity {:user-name "john" :created-at "2024-01-01"}]
      (is (= {:user_name "john" :created_at "2024-01-01"}
             (#'crud/entity->row entity nil)))))

  (testing "converts JSONB fields to PGobject"
    (let [entity {:data {:key "value"} :name "test"}
          fields {:data {:type :jsonb}}
          result (#'crud/entity->row entity fields)
          data-pg ^PGobject (:data result)]
      (is (= "test" (:name result)))
      (is (instance? PGobject data-pg))
      (is (= "jsonb" (PGobject/.getType data-pg)))
      (is (= "{\"key\":\"value\"}" (PGobject/.getValue data-pg)))))

  (testing "converts enum fields to PGobject"
    (let [entity {:status :active :name "test"}
          fields {:status {:type :enum :enum-name :user-status}}
          result (#'crud/entity->row entity fields)
          status-pg ^PGobject (:status result)]
      (is (= "test" (:name result)))
      (is (instance? PGobject status-pg))
      (is (= "user_status" (PGobject/.getType status-pg)))
      (is (= "active" (PGobject/.getValue status-pg))))))


