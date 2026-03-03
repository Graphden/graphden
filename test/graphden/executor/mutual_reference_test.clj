(ns graphden.executor.mutual-reference-test
  "Tests for mutual and self-references in execution graphs."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.interface :as sp]))


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

          ;; Create fn-schemas
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "get-partner"
                                       :returned-type :jsonb})
          arg-n (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id fn-schema)
                                   :name "n"
                                   :type :int
                                   :required true :first-class false})
          arg-partner (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id fn-schema)
                                         :name "partner"
                                         :type :fn  ; :fn type means callable reference
                                         :required true :first-class false})

          ;; Create two fn instances that reference each other
          fn-a (sp/create-entity storage :fn {:name "fn-a" :fn-schema-id (:id fn-schema)})
          fn-b (sp/create-entity storage :fn {:name "fn-b" :fn-schema-id (:id fn-schema)})

          ;; fn-a's n = 1, partner = fn-b
          _ (setup/create-arg-value-with-binding! storage (:id fn-a) (:id arg-n) 1)
          _ (setup/create-arg-value-with-binding! storage (:id fn-a) (:id arg-partner) (:id fn-b))

          ;; fn-b's n = 2, partner = fn-a
          _ (setup/create-arg-value-with-binding! storage (:id fn-b) (:id arg-n) 2)
          _ (setup/create-arg-value-with-binding! storage (:id fn-b) (:id arg-partner) (:id fn-a))

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
                                   :required true :first-class false})
          arg-self (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "self-ref"
                                      :type :fn
                                      :required true :first-class false})

          fn-rec (sp/create-entity storage :fn
                                   {:name "my-self-fn"
                                    :fn-schema-id (:id fn-schema)})

          ;; n = 42
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-n) 42)
          ;; Self-reference
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-self) (:id fn-rec))

          ctx (exec/create-context {:storage storage})]

      (try
        (let [result (exec/execute ctx (:id fn-rec) {})]
          (is (= 42 (:n result)))
          (is (true? (:self-is-uuid result))))
        (finally
          (sp/close storage))))))
