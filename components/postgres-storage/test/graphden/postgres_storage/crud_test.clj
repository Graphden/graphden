(ns graphden.postgres-storage.crud-test
  "Unit tests for PostgreSQL CRUD functions that don't require a database."
  (:require
    [clojure.string :as str]
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


;; === PGobject Parsing Tests ===

(deftest parse-pgobject-jsonb-test
  (testing "parses valid JSONB object"
    (let [pg (make-pgobject "jsonb" "{\"key\": \"value\", \"num\": 42}")]
      (is (= {:key "value" :num 42} (#'crud/parse-pgobject pg)))))

  (testing "parses JSONB array"
    (let [pg (make-pgobject "jsonb" "[1, 2, 3]")]
      (is (= [1 2 3] (#'crud/parse-pgobject pg)))))

  (testing "parses JSONB null"
    (let [pg (make-pgobject "jsonb" "null")]
      (is (nil? (#'crud/parse-pgobject pg)))))

  (testing "parses JSONB string"
    (let [pg (make-pgobject "jsonb" "\"hello\"")]
      (is (= "hello" (#'crud/parse-pgobject pg)))))

  (testing "parses nested JSONB"
    (let [pg (make-pgobject "jsonb" "{\"nested\": {\"deep\": true}}")]
      (is (= {:nested {:deep true}} (#'crud/parse-pgobject pg))))))


(deftest parse-pgobject-non-jsonb-test
  (testing "returns raw value for non-jsonb PGobject types"
    (let [pg (make-pgobject "text" "some text value")]
      (is (= "some text value" (#'crud/parse-pgobject pg)))))

  (testing "returns raw value for uuid type"
    (let [pg (make-pgobject "uuid" "550e8400-e29b-41d4-a716-446655440000")]
      (is (= "550e8400-e29b-41d4-a716-446655440000" (#'crud/parse-pgobject pg))))))


(deftest parse-pgobject-non-pgobject-test
  (testing "returns non-PGobject values unchanged"
    (is (= "string" (#'crud/parse-pgobject "string")))
    (is (= 42 (#'crud/parse-pgobject 42)))
    (is (nil? (#'crud/parse-pgobject nil)))
    (is (= [1 2 3] (#'crud/parse-pgobject [1 2 3])))))


(deftest parse-pgobject-invalid-jsonb-test
  (testing "throws parse error for invalid JSONB"
    (let [pg (make-pgobject "jsonb" "not valid json")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Failed to parse JSONB value"
            (#'crud/parse-pgobject pg)))))

  (testing "includes context in error for invalid JSONB"
    (let [pg (make-pgobject "jsonb" "{invalid")]
      (try
        (#'crud/parse-pgobject pg)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :parse-error/jsonb (:type data)))
            (is (some? (:raw-value data)))
            (is (some? (:cause data))))))))

  (testing "truncates long values in error message"
    (let [long-value (str/join (repeat 200 "x"))
          pg (make-pgobject "jsonb" long-value)]
      (try
        (#'crud/parse-pgobject pg)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [raw-value (:raw-value (ex-data e))]
            (is (str/ends-with? raw-value "...") "should be truncated")
            (is (<= (count raw-value) 104))))))))


;; === Row/Entity Conversion Tests ===

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
             (#'crud/entity->row entity #{})))))

  (testing "converts JSONB fields to PGobject"
    (let [entity {:data {:key "value"} :name "test"}
          result (#'crud/entity->row entity #{:data})
          data-pg ^PGobject (:data result)]
      (is (= "test" (:name result)))
      (is (instance? PGobject data-pg))
      (is (= "jsonb" (PGobject/.getType data-pg)))
      (is (= "{\"key\":\"value\"}" (PGobject/.getValue data-pg))))))


;; === JSONB Column Extraction Tests ===

(deftest extract-jsonb-columns-test
  (testing "extracts fields with jsonb type and includes fallback"
    ;; The function always includes fallback-jsonb-columns (:value)
    (let [fields {:data {:type :jsonb}
                  :name {:type :text}
                  :config {:type :jsonb}}]
      (is (= #{:data :config :value} (#'crud/extract-jsonb-columns fields)))))

  (testing "returns fallback columns when no jsonb fields"
    (let [fields {:name {:type :text}
                  :count {:type :int}}]
      (is (= #{:value} (#'crud/extract-jsonb-columns fields)))))

  (testing "returns fallback columns for nil fields"
    (is (= #{:value} (#'crud/extract-jsonb-columns nil)))))


;; === maybe-wrap-jsonb Tests ===

(deftest maybe-wrap-jsonb-test
  (testing "wraps value as JSONB when column is in jsonb-columns"
    (let [jsonb-cols #{:data :config}
          result (#'crud/maybe-wrap-jsonb jsonb-cols :data {:key "value"})]
      (is (instance? PGobject result))
      (is (= "jsonb" (PGobject/.getType result)))))

  (testing "returns value unchanged when not in jsonb-columns"
    (let [jsonb-cols #{:data}
          result (#'crud/maybe-wrap-jsonb jsonb-cols :name "test")]
      (is (= "test" result))))

  (testing "returns nil unchanged (SQL NULL)"
    (let [jsonb-cols #{:data}
          result (#'crud/maybe-wrap-jsonb jsonb-cols :data nil)]
      (is (nil? result))))

  (testing "returns PGobject unchanged when already wrapped"
    (let [jsonb-cols #{:data}
          pg (make-pgobject "jsonb" "{\"existing\": true}")
          result (#'crud/maybe-wrap-jsonb jsonb-cols :data pg)]
      ;; Should return the same PGobject, not double-wrap
      (is (identical? pg result))
      (is (= "{\"existing\": true}" (PGobject/.getValue result))))))


;; === merge-arg-values-for-chain Tests ===

(deftest merge-arg-values-for-chain-test
  (testing "returns nil for empty chain"
    (is (nil? (#'crud/merge-arg-values-for-chain [] []))))

  (testing "returns nil for nil chain via (seq nil)"
    (is (nil? (#'crud/merge-arg-values-for-chain [] nil))))

  (testing "child arg-value overrides parent"
    (let [parent-fn-id (random-uuid)
          child-fn-id (random-uuid)
          arg-schema-id (random-uuid)
          arg-values [{:id (random-uuid)
                       :owner-fn-id parent-fn-id
                       :arg-schema-id arg-schema-id
                       :value "parent-value"}
                      {:id (random-uuid)
                       :owner-fn-id child-fn-id
                       :arg-schema-id arg-schema-id
                       :value "child-value"}]
          ;; Chain: child -> parent (child first = lower position = wins)
          chain [child-fn-id parent-fn-id]
          result (#'crud/merge-arg-values-for-chain arg-values chain)]
      (is (= "child-value" (:value (get result arg-schema-id))))))

  (testing "uses Integer/MAX_VALUE fallback for unknown owner"
    ;; This tests the edge case where an arg-value has an owner not in the chain
    (let [known-fn-id (random-uuid)
          unknown-fn-id (random-uuid)
          arg-schema-id (random-uuid)
          arg-values [{:id (random-uuid)
                       :owner-fn-id known-fn-id
                       :arg-schema-id arg-schema-id
                       :value "known"}
                      {:id (random-uuid)
                       :owner-fn-id unknown-fn-id
                       :arg-schema-id arg-schema-id
                       :value "unknown"}]
          chain [known-fn-id]
          result (#'crud/merge-arg-values-for-chain arg-values chain)]
      ;; Known owner should win (has lower position than MAX_VALUE)
      (is (= "known" (:value (get result arg-schema-id))))))

  (testing "handles multiple arg-schemas correctly"
    (let [fn-id (random-uuid)
          arg-schema-1 (random-uuid)
          arg-schema-2 (random-uuid)
          arg-values [{:owner-fn-id fn-id :arg-schema-id arg-schema-1 :value 1}
                      {:owner-fn-id fn-id :arg-schema-id arg-schema-2 :value 2}]
          chain [fn-id]
          result (#'crud/merge-arg-values-for-chain arg-values chain)]
      (is (= 1 (:value (get result arg-schema-1))))
      (is (= 2 (:value (get result arg-schema-2)))))))
