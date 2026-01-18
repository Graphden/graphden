(ns graphden.executor.type-validation-test
  "Type validation tests for executor.

   Covers:
   - Type validation tests
   - Mutual reference tests
   - Edge case tests for error paths
   - Additional type validation tests"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage-protocol.interface :as sp]))


(use-fixtures :each exec/with-clean-registry)


;; === Type Validation Tests ===

(deftest provided-arg-type-validation-test
  (testing "throws when :fn type arg is provided with non-UUID value"
    (let [storage (setup/create-test-storage)
          ;; Register HOF that takes a function
          _ (exec/register-base-fn!
              :apply-fn
              (fn [{:keys [f]} _ctx]
                @f))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "apply-fn"
                                       :returned-type :int})
          f-arg (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id fn-schema)
                                   :name "f"
                                   :type :fn
                                   :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-apply"
                                    :fn-schema-id (:id fn-schema)})
          ;; Create dummy arg-value (will be overridden)
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id f-arg)
                               :value (random-uuid)})
          ctx (exec/create-context {:storage storage})]
      ;; Provide a string instead of UUID for :fn type
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'f': expected fn"
            (exec/execute ctx (:id fn-rec) {(:id f-arg) "not-a-uuid"})))
      (sp/close storage)))

  (testing "throws when :ref type arg is provided with non-UUID value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-ref
              (fn [{:keys [r]} _ctx]
                @r))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-ref"
                                       :returned-type :int})
          r-arg (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id fn-schema)
                                   :name "r"
                                   :type :ref
                                   :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-ref"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id r-arg)
                               :value (random-uuid)})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'r': expected ref"
            (exec/execute ctx (:id fn-rec) {(:id r-arg) 12345})))
      (sp/close storage)))

  (testing "throws when :int type arg is provided with non-integer value"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 1})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 2})
          ctx (exec/create-context {:storage storage})]
      ;; Provide a string instead of int
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'a': expected int"
            (exec/execute ctx (:id fn-rec) {(:id arg-a) "not-an-int"})))
      (sp/close storage)))

  (testing "throws when :bool type arg is provided with non-boolean value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-bool
              (fn [{:keys [flag]} _ctx]
                (if @flag "yes" "no")))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-bool"
                                       :returned-type :text})
          flag-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "flag"
                                      :type :bool
                                      :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-bool"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id flag-arg)
                               :value true})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'flag': expected bool"
            (exec/execute ctx (:id fn-rec) {(:id flag-arg) "true"})))
      (sp/close storage)))

  (testing "throws when :text type arg is provided with non-string value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-text
              (fn [{:keys [msg]} _ctx]
                (str "Message: " @msg)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-text"
                                       :returned-type :text})
          msg-arg (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id fn-schema)
                                     :name "msg"
                                     :type :text
                                     :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-text"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id msg-arg)
                               :value "hello"})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'msg': expected text"
            (exec/execute ctx (:id fn-rec) {(:id msg-arg) 12345})))
      (sp/close storage)))

  (testing "valid types pass without throwing"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 1})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 2})
          ctx (exec/create-context {:storage storage})
          ;; Provide valid int values
          result (exec/execute ctx (:id fn-rec) {(:id arg-a) 10
                                                 (:id arg-b) 20})]
      (is (= 30 result))
      (sp/close storage)))

  (testing "other types (like :numeric) pass without strict validation"
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
                                   :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-numeric"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id n-arg)
                               :value 3.14})
          ctx (exec/create-context {:storage storage})
          ;; Numeric allows various number types - should not throw
          result (exec/execute ctx (:id fn-rec) {(:id n-arg) 2.718})]
      (is (= 2.718 result))
      (sp/close storage))))


;; === Mutual Reference Tests ===

(deftest mutual-reference-graph-test
  (testing "mutual references are correctly resolved in execution graph"
    ;; This tests that functions referencing each other (A -> B, B -> A)
    ;; are correctly resolved without infinite loops in graph resolution
    (let [storage (setup/create-test-storage)
          ;; Register base functions that use :fn type args
          ;; :fn type args now return fn-ids (UUIDs), not callables
          _ (exec/register-base-fn!
              :get-partner
              (fn [{:keys [n partner]} _ctx]
                (let [n-val @n
                      ;; partner is now a fn-id (UUID)
                      partner-id @partner]
                  {:n n-val :partner-is-uuid (uuid? partner-id)})))

          ;; Create fn-schemas
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "get-partner"
                                       :returned-type :jsonb})
          arg-n (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id fn-schema)
                                   :name "n"
                                   :type :int
                                   :required true})
          arg-partner (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id fn-schema)
                                         :name "partner"
                                         :type :fn  ; :fn type means callable reference
                                         :required true})

          ;; Create two fn instances that reference each other
          fn-a (sp/create-entity storage :fn {:name "fn-a" :fn-schema-id (:id fn-schema)})
          fn-b (sp/create-entity storage :fn {:name "fn-b" :fn-schema-id (:id fn-schema)})

          ;; fn-a's n = 1, partner = fn-b
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-a)
                               :arg-schema-id (:id arg-n)
                               :value 1})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-a)
                               :arg-schema-id (:id arg-partner)
                               :value (:id fn-b)})

          ;; fn-b's n = 2, partner = fn-a
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-b)
                               :arg-schema-id (:id arg-n)
                               :value 2})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-b)
                               :arg-schema-id (:id arg-partner)
                               :value (:id fn-a)})

          ctx (exec/create-context {:storage storage})]

      (try
        ;; Execute fn-a - partner should be a UUID (fn-id)
        (let [result-a (exec/execute ctx (:id fn-a) {})]
          (is (= 1 (:n result-a)))
          (is (true? (:partner-is-uuid result-a))))

        ;; Execute fn-b - partner should be a UUID (fn-id)
        (let [result-b (exec/execute ctx (:id fn-b) {})]
          (is (= 2 (:n result-b)))
          (is (true? (:partner-is-uuid result-b))))
        (finally
          (sp/close storage))))))


(deftest self-reference-test
  (testing "function with self-reference via :fn type arg"
    ;; A function can reference itself. The self-ref is now a fn-id (UUID)
    ;; so forcing it returns a UUID, not causing infinite execution
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :with-self
              (fn [{:keys [n self-ref]} _ctx]
                (let [n-val @n
                      ;; self-ref is a fn-id (UUID)
                      self-id @self-ref]
                  {:n n-val :self-is-uuid (uuid? self-id)})))

          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "with-self"
                                       :returned-type :jsonb})
          arg-n (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id fn-schema)
                                   :name "n"
                                   :type :int
                                   :required true})
          arg-self (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "self-ref"
                                      :type :fn
                                      :required true})

          fn-rec (sp/create-entity storage :fn
                                   {:name "my-self-fn"
                                    :fn-schema-id (:id fn-schema)})

          ;; n = 42
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-n)
                               :value 42})
          ;; Self-reference
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-self)
                               :value (:id fn-rec)})

          ctx (exec/create-context {:storage storage})]

      (try
        (let [result (exec/execute ctx (:id fn-rec) {})]
          (is (= 42 (:n result)))
          (is (true? (:self-is-uuid result))))
        (finally
          (sp/close storage))))))


;; === Edge Case Tests for Error Paths ===

(defn- create-mock-storage
  "Creates a mock storage that returns the specified execution graph."
  [execution-graph]
  (reify
    sp/ExecutionGraph
    (resolve-execution-graph
      [_ _fn-id]
      execution-graph)))


(deftest fn-schema-not-found-in-graph-test
  (testing "throws when fn-schema is missing from execution graph"
    ;; This tests the error path at lines 203-207 of executor/core.clj
    ;; where fn-schema is not found in the execution graph
    (let [fn-id (random-uuid)
          fn-schema-id (random-uuid)
          _ (exec/register-base-fn! :dummy (fn [_ _] nil))
          ;; Create mock storage that returns a graph with fn but missing fn-schema
          mock-storage (create-mock-storage
                         {:fns {fn-id {:id fn-id
                                       :name "my-dummy"
                                       :fn-schema-id fn-schema-id}}
                          :fn-schemas {}  ; Empty - fn-schema is missing!
                          :arg-schemas {}
                          :resolved-args {}})
          ctx (exec/create-context {:storage mock-storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Function schema not found in execution graph"
            (exec/execute ctx fn-id {}))))))


(deftest arg-schema-missing-type-test
  (testing "throws when arg-schema is missing :type field"
    ;; This tests the error path at lines 106-109 of executor/core.clj
    ;; where arg-schema is nil or missing :type
    (let [fn-id (random-uuid)
          fn-schema-id (random-uuid)
          bad-arg-schema-id (random-uuid)
          _ (exec/register-base-fn! :dummy (fn [_ _] nil))
          ;; Create mock storage with arg-schema missing :type
          mock-storage (create-mock-storage
                         {:fns {fn-id {:id fn-id
                                       :name "my-dummy"
                                       :fn-schema-id fn-schema-id}}
                          :fn-schemas {fn-schema-id {:id fn-schema-id
                                                     :name "dummy"
                                                     :returned-type :int}}
                          ;; arg-schema without :type field
                          :arg-schemas {bad-arg-schema-id {:id bad-arg-schema-id
                                                           :fn-schema-id fn-schema-id
                                                           :name "x"
                                                           :required true}}
                          :resolved-args {fn-id
                                          {bad-arg-schema-id {:owner-fn-id fn-id
                                                              :arg-schema-id bad-arg-schema-id
                                                              :value 42}}}})
          ctx (exec/create-context {:storage mock-storage})]
      ;; When we try to provide an arg that will trigger validation on malformed schema
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid arg-schema: missing type"
            (exec/execute ctx fn-id {bad-arg-schema-id 100}))))))


;; === Additional Type Validation Tests ===

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
                                   :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-numeric"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id n-arg)
                               :value 3.14})
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
                                   :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-numeric"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id n-arg)
                               :value 3.14})
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
                                      :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-jsonb"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id data-arg)
                               :value {:a 1}})
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
                                      :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-jsonb"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id data-arg)
                               :value {:a 1}})
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
                                      :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-jsonb"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id data-arg)
                               :value [1 2 3]})
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
                                      :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-bytes"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id data-arg)
                               :value (byte-array [1 2 3])})
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
                                      :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-bytes"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id data-arg)
                               :value (byte-array [1 2 3])})
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
                                    :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-timestamp"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id ts-arg)
                               :value (java.time.Instant/now)})
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
                                    :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-timestamp"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id ts-arg)
                               :value (java.time.Instant/now)})
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
                                    :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-timestamp"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id ts-arg)
                               :value (java.util.Date.)})
          ctx (exec/create-context {:storage storage})
          test-date (java.util.Date. 0)]
      (is (= test-date (exec/execute ctx (:id fn-rec) {(:id ts-arg) test-date})))
      (sp/close storage))))


(deftest enum-type-validation-test
  (testing "throws when :enum type arg is provided with non-keyword value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-enum
              (fn [{:keys [status]} _ctx]
                @status))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-enum"
                                       :returned-type :text})
          status-arg (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id fn-schema)
                                        :name "status"
                                        :type :enum
                                        :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-enum"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id status-arg)
                               :value :active})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'status': expected enum"
            (exec/execute ctx (:id fn-rec) {(:id status-arg) "not-a-keyword"})))
      (sp/close storage)))

  (testing "accepts valid keyword value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-enum
              (fn [{:keys [status]} _ctx]
                (name @status)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-enum"
                                       :returned-type :text})
          status-arg (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id fn-schema)
                                        :name "status"
                                        :type :enum
                                        :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-enum"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id status-arg)
                               :value :active})
          ctx (exec/create-context {:storage storage})]
      (is (= "pending" (exec/execute ctx (:id fn-rec) {(:id status-arg) :pending})))
      (sp/close storage))))


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
                                    :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-uuid"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id id-arg)
                               :value (random-uuid)})
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
                                    :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-uuid"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id id-arg)
                               :value (random-uuid)})
          ctx (exec/create-context {:storage storage})
          test-uuid #uuid "12345678-1234-1234-1234-123456789abc"]
      (is (= test-uuid (exec/execute ctx (:id fn-rec) {(:id id-arg) test-uuid})))
      (sp/close storage))))
