(ns graphden.executor.provided-arg-type-test
  "Tests for type validation when providing args at runtime for FREE args.
   These tests verify that type checking works for args NOT defined in DB.

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


(deftest provided-arg-type-validation-test
  (testing "throws when :fn type arg is provided with non-UUID value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :apply-fn
              (fn [{:keys [f]} _ctx]
                @f))
          ;; Create base fn (parent-id=nil)
          base-fn (sp/create-entity storage :fn
                                    {:name "apply-fn"
                                     :parent-id nil
                                     :return-type :int})
          ;; Create arg directly on the fn
          f-arg (sp/create-entity storage :arg
                                  {:fn-id (:id base-fn)
                                   :name "f"
                                   :type :fn
                                   :required true
                                   :is-fn true})
          ;; No value/ref-id set - arg is free
          ctx (exec/create-context {:storage storage})]
      ;; Provide a string instead of UUID for :fn type
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'f': expected fn"
            (exec/execute ctx (:id base-fn) {(:id f-arg) "not-a-uuid"})))
      (sp/close storage)))

  (testing "throws when :int type arg is provided with non-integer value"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a]} (setup/setup-add-function! storage)
          ;; No value set - arg is free
          ctx (exec/create-context {:storage storage})]
      ;; Provide a string instead of int
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'a': expected int"
            (exec/execute ctx (:id base-fn) {(:id arg-a) "not-an-int"})))
      (sp/close storage)))

  (testing "throws when :bool type arg is provided with non-boolean value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-bool
              (fn [{:keys [flag]} _ctx]
                (if @flag "yes" "no")))
          base-fn (sp/create-entity storage :fn
                                    {:name "use-bool"
                                     :parent-id nil
                                     :return-type :text})
          flag-arg (sp/create-entity storage :arg
                                     {:fn-id (:id base-fn)
                                      :name "flag"
                                      :type :bool
                                      :required true})
          ;; No value set - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'flag': expected bool"
            (exec/execute ctx (:id base-fn) {(:id flag-arg) "true"})))
      (sp/close storage)))

  (testing "throws when :text type arg is provided with non-string value"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :use-text
              (fn [{:keys [msg]} _ctx]
                (str "Message: " @msg)))
          base-fn (sp/create-entity storage :fn
                                    {:name "use-text"
                                     :parent-id nil
                                     :return-type :text})
          msg-arg (sp/create-entity storage :arg
                                    {:fn-id (:id base-fn)
                                     :name "msg"
                                     :type :text
                                     :required true})
          ;; No value set - arg is free
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'msg': expected text"
            (exec/execute ctx (:id base-fn) {(:id msg-arg) 12345})))
      (sp/close storage)))

  (testing "valid types pass without throwing"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          ;; No value set - args are free
          ctx (exec/create-context {:storage storage})
          ;; Provide valid int values
          result (exec/execute ctx (:id base-fn) {(:id arg-a) 10
                                                  (:id arg-b) 20})]
      (is (= 30 result))
      (sp/close storage)))

  (testing "other types (like :numeric) pass validation"
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
      ;; Numeric accepts numbers
      (is (= 3.14 (exec/execute ctx (:id base-fn) {(:id n-arg) 3.14})))
      (sp/close storage))))
