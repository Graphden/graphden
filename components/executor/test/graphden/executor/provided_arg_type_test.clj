(ns graphden.executor.provided-arg-type-test
  "Type validation tests for provided arguments."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage-protocol.interface :as sp]))


(use-fixtures :each exec/with-clean-registry)


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
