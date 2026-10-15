(ns ^:serial graphden.crud.delete-batch-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.crud.entities.delete-batch :as batch]
    [graphden.crud.entities.function-batch-budget :as budget]
    [graphden.crud.entities.invalidation :as invalidation]
    [graphden.crud.type-check :as type-check]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]))


(deftest transaction-local-delete-requires-live-writer-test
  (let [connection (Object.)
        other-connection (Object.)
        context {:connection connection :active? (atom true) :writer-scope (atom :global)}
        reads (atom 0)]
    (with-redefs [sp/read-entities (fn [& _] (swap! reads inc) {})]
      (doseq [[active token datasource]
              [[nil nil connection]
               [context nil connection]
               [context context other-connection]
               [(assoc context :active? (atom false)) context connection]
               [(assoc context :writer-scope (atom nil)) context connection]]]
        (binding [tx/*transaction-context* active]
          (with-redefs [tx/datasource (constantly datasource)
                        tx/writer-context (constantly token)]
            (is (= :graph-write/transaction-required
                   (try (batch/delete-locked! {} []) nil
                        (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))))
      (is (zero? @reads)))))


(deftest deletion-publication-refuses-uncommitted-transaction-test
  (binding [tx/*transaction-context* {:connection (Object.) :active? (atom true)}]
    (is (= :graph-write/commit-boundary-required
           (try (batch/publish! {} nil {}) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))


(deftest parse-exact-receipts-test
  (let [row {:id (str (random-uuid)) :name "owned" :namespace-id (str (random-uuid))}
        parsed (batch/parse-receipts {:functions [row]})]
    (is (= [(-> row (update :id parse-uuid) (update :namespace-id parse-uuid))] parsed))
    (is (= parsed (batch/parse-receipts {:functions parsed})))
    (doseq [input [nil false 1 "functions" [] {} {:functions nil} {:functions []}
                   {:functions row} {:functions [row row]} {:functions [row] :extra true}
                   {:functions (vec (repeat (inc budget/max-functions) row))}
                   {:functions [nil]} {:functions [42]}
                   {:functions [(assoc row :id "bogus")]}
                   {:functions [(assoc row :namespace-id [])]}
                   {:functions [(assoc row :name " ")]}
                   {:functions [(dissoc row :namespace-id)]}
                   {:functions [(assoc row :extra "not allowed")]}]]
      (is (= :validation-error/delete-receipts
             (try (batch/parse-receipts input) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))))


(deftest exact-receipt-count-boundary-test
  (let [namespace-id (str (random-uuid))
        rows (mapv (fn [index]
                     {:id (str (random-uuid)) :name (str "owned-" index)
                      :namespace-id namespace-id})
                   (range (inc budget/max-functions)))]
    (is (= budget/max-functions
           (count (batch/parse-receipts {:functions (pop rows)}))))
    (is (= :validation-error/delete-receipts
           (try (batch/parse-receipts {:functions rows}) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))


(deftest bulk-delete-rechecks-surviving-diamond-once-test
  (let [[a b c d] (repeatedly 4 random-uuid)
        checked (atom [])]
    (with-redefs [runtime/ctx-reverse-deps (constantly {a #{b c} b #{c} c #{d}})
                  type-check/type-check-fn-after-mutation!
                  (fn [_ id] (swap! checked conj id) nil)]
      (is (= #{c d} (type-check/recheck-deleted-fns! {} nil #{a b})))
      (is (= [c d] @checked)))))


(deftest compound-invalidation-retains-exact-seeds-test
  (let [ids (set (repeatedly 3 random-uuid))]
    (is (= ids (invalidation/affected-fn-ids nil :fn {:ids ids})))
    (is (= #{} (invalidation/affected-fn-ids nil :fn {:ids []})))
    (is (nil? (invalidation/affected-fn-ids nil :fn {})))))
