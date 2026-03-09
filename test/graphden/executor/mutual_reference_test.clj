(ns graphden.executor.mutual-reference-test
  "Tests for mutual and self-references in execution graphs.

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

          ;; Create base fn
          base-fn (setup/create-base-fn! storage "get-partner" :jsonb)
          arg-n (setup/create-arg! storage (:id base-fn)
                                   {:name "n" :type :int :required true :is-fn false})
          arg-partner (setup/create-arg! storage (:id base-fn)
                                         {:name "partner" :type :fn :required true :is-fn true})

          ;; Create two composed fn instances that reference each other
          fn-a (setup/create-composed-fn! storage "fn-a" (:id base-fn))
          fn-b (setup/create-composed-fn! storage "fn-b" (:id base-fn))

          ;; fn-a's n = 1, partner = fn-b via ref-id
          _ (setup/create-arg! storage (:id fn-a)
                               {:name "n" :type :int :required true :is-fn false
                                :source-id (:id arg-n) :value 1})
          _ (setup/create-arg! storage (:id fn-a)
                               {:name "partner" :type :fn :required true :is-fn true
                                :source-id (:id arg-partner) :ref-id (:id fn-b)})

          ;; fn-b's n = 2, partner = fn-a via ref-id
          _ (setup/create-arg! storage (:id fn-b)
                               {:name "n" :type :int :required true :is-fn false
                                :source-id (:id arg-n) :value 2})
          _ (setup/create-arg! storage (:id fn-b)
                               {:name "partner" :type :fn :required true :is-fn true
                                :source-id (:id arg-partner) :ref-id (:id fn-a)})

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

          ;; Create base fn
          base-fn (setup/create-base-fn! storage "with-self" :jsonb)
          arg-n (setup/create-arg! storage (:id base-fn)
                                   {:name "n" :type :int :required true :is-fn false})
          arg-self (setup/create-arg! storage (:id base-fn)
                                      {:name "self-ref" :type :fn :required true :is-fn true})

          ;; Create composed fn
          fn-rec (setup/create-composed-fn! storage "my-self-fn" (:id base-fn))

          ;; n = 42
          _ (setup/create-arg! storage (:id fn-rec)
                               {:name "n" :type :int :required true :is-fn false
                                :source-id (:id arg-n) :value 42})
          ;; Self-reference via ref-id
          _ (setup/create-arg! storage (:id fn-rec)
                               {:name "self-ref" :type :fn :required true :is-fn true
                                :source-id (:id arg-self) :ref-id (:id fn-rec)})

          ctx (exec/create-context {:storage storage})]

      (try
        (let [result (exec/execute ctx (:id fn-rec) {})]
          (is (= 42 (:n result)))
          (is (true? (:self-is-uuid result))))
        (finally
          (sp/close storage))))))
