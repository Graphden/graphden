(ns graphden.executor.additional-type-test
  "Additional type validation tests for numeric, jsonb, bytes, timestamptz, enum, uuid.
   Tests validation of provided args (free args) - DB values are not set so validation occurs.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
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
          ;; Create base fn (parent-id=nil)
          base-fn (sp/create-entity storage :fn
                                    {:name "use-numeric"
                                     :parent-id nil
                                     :return-type :numeric})
          n-arg (sp/create-entity storage :arg
                                  {:fn-id (:id base-fn)
                                   :name "n"
                                   :type :numeric
                                   :required true})
          ;; No value set - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'n': expected numeric"
            (exec/execute ctx (:id base-fn) {(:id n-arg) "not-a-number"})))
      (sp/close storage)))

  (testing "accepts valid numeric values"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-numeric
              (fn [{:keys [n]} _ctx]
                @n))
          base-fn (sp/create-entity storage :fn
                                    {:name "use-numeric"
                                     :parent-id nil
                                     :return-type :numeric})
          n-arg (sp/create-entity storage :arg
                                  {:fn-id (:id base-fn)
                                   :name "n"
                                   :type :numeric
                                   :required true})
          ;; No value set - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (= 2.718M (exec/execute ctx (:id base-fn) {(:id n-arg) 2.718M})))
      (sp/close storage))))


(deftest jsonb-type-validation-test
  ;; Note: :jsonb now accepts any JSON-serializable value (strings, numbers, etc.)
  ;; See src/graphden/schema/fields/types.clj for rationale
  (testing "accepts string values for :jsonb type (any JSON-serializable value)"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-jsonb
              (fn [{:keys [data]} _ctx]
                @data))
          base-fn (sp/create-entity storage :fn
                                    {:name "use-jsonb"
                                     :parent-id nil
                                     :return-type :jsonb})
          data-arg (sp/create-entity storage :arg
                                     {:fn-id (:id base-fn)
                                      :name "data"
                                      :type :jsonb
                                      :required true})
          ;; No value set - arg is free
          ctx (exec/create-context {:storage storage})]
      ;; Strings are valid JSON values
      (is (= "a-string-value" (exec/execute ctx (:id base-fn) {(:id data-arg) "a-string-value"})))
      (sp/close storage)))

  (testing "accepts valid jsonb values (map)"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-jsonb
              (fn [{:keys [data]} _ctx]
                @data))
          base-fn (sp/create-entity storage :fn
                                    {:name "use-jsonb"
                                     :parent-id nil
                                     :return-type :jsonb})
          data-arg (sp/create-entity storage :arg
                                     {:fn-id (:id base-fn)
                                      :name "data"
                                      :type :jsonb
                                      :required true})
          ;; No value set - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (= {:x 1 :y 2} (exec/execute ctx (:id base-fn) {(:id data-arg) {:x 1 :y 2}})))
      (sp/close storage)))

  (testing "accepts valid jsonb values (vector)"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-jsonb
              (fn [{:keys [data]} _ctx]
                @data))
          base-fn (sp/create-entity storage :fn
                                    {:name "use-jsonb"
                                     :parent-id nil
                                     :return-type :jsonb})
          data-arg (sp/create-entity storage :arg
                                     {:fn-id (:id base-fn)
                                      :name "data"
                                      :type :jsonb
                                      :required true})
          ;; No value set - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (= [4 5 6] (exec/execute ctx (:id base-fn) {(:id data-arg) [4 5 6]})))
      (sp/close storage))))


(deftest bytes-type-validation-test
  (testing "throws when :bytes type arg is provided with non-byte-array value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-bytes
              (fn [{:keys [data]} _ctx]
                @data))
          base-fn (sp/create-entity storage :fn
                                    {:name "use-bytes"
                                     :parent-id nil
                                     :return-type :bytes})
          data-arg (sp/create-entity storage :arg
                                     {:fn-id (:id base-fn)
                                      :name "data"
                                      :type :bytes
                                      :required true})
          ;; No value set - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'data': expected bytes"
            (exec/execute ctx (:id base-fn) {(:id data-arg) "not-bytes"})))
      (sp/close storage)))

  (testing "accepts valid bytes values"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-bytes
              (fn [{:keys [data]} _ctx]
                (vec @data)))
          base-fn (sp/create-entity storage :fn
                                    {:name "use-bytes"
                                     :parent-id nil
                                     :return-type :jsonb})
          data-arg (sp/create-entity storage :arg
                                     {:fn-id (:id base-fn)
                                      :name "data"
                                      :type :bytes
                                      :required true})
          ;; No value set - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (= [4 5 6] (exec/execute ctx (:id base-fn) {(:id data-arg) (byte-array [4 5 6])})))
      (sp/close storage))))


(deftest timestamptz-type-validation-test
  (testing "throws when :timestamptz type arg is provided with invalid value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-timestamp
              (fn [{:keys [ts]} _ctx]
                @ts))
          base-fn (sp/create-entity storage :fn
                                    {:name "use-timestamp"
                                     :parent-id nil
                                     :return-type :timestamptz})
          ts-arg (sp/create-entity storage :arg
                                   {:fn-id (:id base-fn)
                                    :name "ts"
                                    :type :timestamptz
                                    :required true})
          ;; No value set - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'ts': expected timestamptz"
            (exec/execute ctx (:id base-fn) {(:id ts-arg) "not-a-timestamp"})))
      (sp/close storage)))

  (testing "accepts valid Instant value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-timestamp
              (fn [{:keys [ts]} _ctx]
                @ts))
          base-fn (sp/create-entity storage :fn
                                    {:name "use-timestamp"
                                     :parent-id nil
                                     :return-type :timestamptz})
          ts-arg (sp/create-entity storage :arg
                                   {:fn-id (:id base-fn)
                                    :name "ts"
                                    :type :timestamptz
                                    :required true})
          ;; No value set - arg is free
          ctx (exec/create-context {:storage storage})
          test-instant (java.time.Instant/parse "2024-01-01T00:00:00Z")]
      (is (= test-instant (exec/execute ctx (:id base-fn) {(:id ts-arg) test-instant})))
      (sp/close storage)))

  (testing "accepts valid Date value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-timestamp
              (fn [{:keys [ts]} _ctx]
                @ts))
          base-fn (sp/create-entity storage :fn
                                    {:name "use-timestamp"
                                     :parent-id nil
                                     :return-type :timestamptz})
          ts-arg (sp/create-entity storage :arg
                                   {:fn-id (:id base-fn)
                                    :name "ts"
                                    :type :timestamptz
                                    :required true})
          ;; No value set - arg is free
          ctx (exec/create-context {:storage storage})
          test-date (java.util.Date. 0)]
      (is (= test-date (exec/execute ctx (:id base-fn) {(:id ts-arg) test-date})))
      (sp/close storage))))


(deftest uuid-type-validation-test
  (testing "throws when :uuid type arg is provided with non-UUID value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-uuid
              (fn [{:keys [id]} _ctx]
                @id))
          base-fn (sp/create-entity storage :fn
                                    {:name "use-uuid"
                                     :parent-id nil
                                     :return-type :uuid})
          id-arg (sp/create-entity storage :arg
                                   {:fn-id (:id base-fn)
                                    :name "id"
                                    :type :uuid
                                    :required true})
          ;; No value set - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'id': expected uuid"
            (exec/execute ctx (:id base-fn) {(:id id-arg) "not-a-uuid"})))
      (sp/close storage)))

  (testing "accepts valid UUID value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-uuid
              (fn [{:keys [id]} _ctx]
                @id))
          base-fn (sp/create-entity storage :fn
                                    {:name "use-uuid"
                                     :parent-id nil
                                     :return-type :uuid})
          id-arg (sp/create-entity storage :arg
                                   {:fn-id (:id base-fn)
                                    :name "id"
                                    :type :uuid
                                    :required true})
          ;; No value set - arg is free
          ctx (exec/create-context {:storage storage})
          test-uuid #uuid "12345678-1234-1234-1234-123456789abc"]
      (is (= test-uuid (exec/execute ctx (:id base-fn) {(:id id-arg) test-uuid})))
      (sp/close storage))))
