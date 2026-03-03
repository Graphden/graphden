(ns graphden.executor.provided-arg-type-test
  "Tests for type validation when providing args at runtime for FREE args.
   These tests verify that type checking works for args NOT defined in DB."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.interface :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each
  (setup/create-clean-db-fixture)
  exec/with-clean-registry)


(deftest provided-arg-type-validation-test
  (testing "throws when :fn type arg is provided with non-UUID value"
    (let [storage (setup/create-test-storage)
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
                                   :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-apply"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free
          ctx (exec/create-context {:storage storage})]
      ;; Provide a string instead of UUID for :fn type
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'f': expected fn"
            (exec/execute ctx (:id fn-rec) {(:id f-arg) "not-a-uuid"})))
      (sp/close storage)))

  ;; NOTE: :ref type test removed because :ref is not a valid type in value_kind enum.
  ;; References are handled via :fn type or through arg-value union variants.

  (testing "throws when :int type arg is provided with non-integer value"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a]} (setup/setup-add-function! storage)
          ;; No arg-value in DB - arg is free
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
                                      :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-bool"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free
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
                                     :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-text"
                                    :fn-schema-id (:id fn-schema)})
          ;; No arg-value in DB - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'msg': expected text"
            (exec/execute ctx (:id fn-rec) {(:id msg-arg) 12345})))
      (sp/close storage)))

  (testing "valid types pass without throwing"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          ;; No arg-value in DB - args are free
          ctx (exec/create-context {:storage storage})
          ;; Provide valid int values
          result (exec/execute ctx (:id fn-rec) {(:id arg-a) 10
                                                 (:id arg-b) 20})]
      (is (= 30 result))
      (sp/close storage)))

  (testing "other types (like :numeric) pass validation"
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
      ;; Numeric accepts numbers
      (is (= 3.14 (exec/execute ctx (:id fn-rec) {(:id n-arg) 3.14})))
      (sp/close storage))))
