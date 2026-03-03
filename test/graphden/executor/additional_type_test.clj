(ns graphden.executor.additional-type-test
  "Additional type validation tests for numeric, jsonb, bytes, timestamptz, enum, uuid.
   Tests validation of provided args (free args) - DB values are not set so validation occurs."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each
  (setup/create-clean-db-fixture)
  exec/with-clean-registry)


(deftest numeric-type-validation-test
  (testing "throws when :numeric type arg is provided with non-number value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-numeric
              (fn [{:keys [n]} _ctx]
                @n))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-numeric"
                                       :returned-type :numeric})
          n-arg (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id fn-schema)
                                   :name "n"
                                   :type :numeric
                                   :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-numeric"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free, test provides value via execute
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'n': expected numeric"
            (exec/execute ctx (:id fn-rec) {(:id n-arg) "not-a-number"})))
      (sp/close storage)))

  (testing "accepts valid numeric values"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-numeric
              (fn [{:keys [n]} _ctx]
                @n))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-numeric"
                                       :returned-type :numeric})
          n-arg (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id fn-schema)
                                   :name "n"
                                   :type :numeric
                                   :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-numeric"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (= 2.718M (exec/execute ctx (:id fn-rec) {(:id n-arg) 2.718M})))
      (sp/close storage))))


(deftest jsonb-type-validation-test
  (testing "throws when :jsonb type arg is provided with non-map/vector value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-jsonb
              (fn [{:keys [data]} _ctx]
                @data))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-jsonb"
                                       :returned-type :jsonb})
          data-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "data"
                                      :type :jsonb
                                      :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-jsonb"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'data': expected jsonb"
            (exec/execute ctx (:id fn-rec) {(:id data-arg) "not-jsonb"})))
      (sp/close storage)))

  (testing "accepts valid jsonb values (map)"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-jsonb
              (fn [{:keys [data]} _ctx]
                @data))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-jsonb"
                                       :returned-type :jsonb})
          data-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "data"
                                      :type :jsonb
                                      :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-jsonb"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (= {:x 1 :y 2} (exec/execute ctx (:id fn-rec) {(:id data-arg) {:x 1 :y 2}})))
      (sp/close storage)))

  (testing "accepts valid jsonb values (vector)"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-jsonb
              (fn [{:keys [data]} _ctx]
                @data))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-jsonb"
                                       :returned-type :jsonb})
          data-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "data"
                                      :type :jsonb
                                      :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-jsonb"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (= [4 5 6] (exec/execute ctx (:id fn-rec) {(:id data-arg) [4 5 6]})))
      (sp/close storage))))


(deftest bytes-type-validation-test
  (testing "throws when :bytes type arg is provided with non-byte-array value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-bytes
              (fn [{:keys [data]} _ctx]
                @data))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-bytes"
                                       :returned-type :bytes})
          data-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "data"
                                      :type :bytes
                                      :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-bytes"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'data': expected bytes"
            (exec/execute ctx (:id fn-rec) {(:id data-arg) "not-bytes"})))
      (sp/close storage)))

  (testing "accepts valid bytes values"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-bytes
              (fn [{:keys [data]} _ctx]
                (vec @data)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-bytes"
                                       :returned-type :jsonb})
          data-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "data"
                                      :type :bytes
                                      :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-bytes"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (= [4 5 6] (exec/execute ctx (:id fn-rec) {(:id data-arg) (byte-array [4 5 6])})))
      (sp/close storage))))


(deftest timestamptz-type-validation-test
  (testing "throws when :timestamptz type arg is provided with invalid value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-timestamp
              (fn [{:keys [ts]} _ctx]
                @ts))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-timestamp"
                                       :returned-type :timestamptz})
          ts-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id fn-schema)
                                    :name "ts"
                                    :type :timestamptz
                                    :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-timestamp"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'ts': expected timestamptz"
            (exec/execute ctx (:id fn-rec) {(:id ts-arg) "not-a-timestamp"})))
      (sp/close storage)))

  (testing "accepts valid Instant value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-timestamp
              (fn [{:keys [ts]} _ctx]
                @ts))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-timestamp"
                                       :returned-type :timestamptz})
          ts-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id fn-schema)
                                    :name "ts"
                                    :type :timestamptz
                                    :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-timestamp"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free
          ctx (exec/create-context {:storage storage})
          test-instant (java.time.Instant/parse "2024-01-01T00:00:00Z")]
      (is (= test-instant (exec/execute ctx (:id fn-rec) {(:id ts-arg) test-instant})))
      (sp/close storage)))

  (testing "accepts valid Date value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-timestamp
              (fn [{:keys [ts]} _ctx]
                @ts))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-timestamp"
                                       :returned-type :timestamptz})
          ts-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id fn-schema)
                                    :name "ts"
                                    :type :timestamptz
                                    :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-timestamp"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free
          ctx (exec/create-context {:storage storage})
          test-date (java.util.Date. 0)]
      (is (= test-date (exec/execute ctx (:id fn-rec) {(:id ts-arg) test-date})))
      (sp/close storage))))


;; NOTE: enum-type-validation-test was removed because :enum is not a
;; valid type in the value_kind PostgreSQL enum. The schema only supports:
;; :null, :uuid, :text, :int, :bool, :numeric, :timestamptz, :jsonb, :bytes, :any, :fn
;;
;; Keyword/enum values can be stored using :jsonb or :text types.


(deftest uuid-type-validation-test
  (testing "throws when :uuid type arg is provided with non-UUID value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-uuid
              (fn [{:keys [id]} _ctx]
                @id))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-uuid"
                                       :returned-type :uuid})
          id-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id fn-schema)
                                    :name "id"
                                    :type :uuid
                                    :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-uuid"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'id': expected uuid"
            (exec/execute ctx (:id fn-rec) {(:id id-arg) "not-a-uuid"})))
      (sp/close storage)))

  (testing "accepts valid UUID value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-uuid
              (fn [{:keys [id]} _ctx]
                @id))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-uuid"
                                       :returned-type :uuid})
          id-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id fn-schema)
                                    :name "id"
                                    :type :uuid
                                    :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-uuid"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free
          ctx (exec/create-context {:storage storage})
          test-uuid #uuid "12345678-1234-1234-1234-123456789abc"]
      (is (= test-uuid (exec/execute ctx (:id fn-rec) {(:id id-arg) test-uuid})))
      (sp/close storage))))
