(ns ^:integration graphden.integration.find-fn-usages-graph-test
  "Graph-path tests for `:find-fn-usages` — the production HTTP path's
   reverse-reference scan that backs the `DELETE /api/secrets/:id`
   `in-use?` guard.

   `:find-fn-usages` is the graph fn-def that REPLACED the `defbase
   find-fn-usages` thin-shim that used to delegate to
   `graphden.crud.secrets/find-usages`. The Clojure helper is still
   used by the test orchestrator (`crud.secrets/delete-secret`); these
   tests cover the OTHER path — the production HTTP graph composition
   over `:query-ref-many-owners` + 2 `:list-entities` reverse-ref scans
   + `:merge`-precedence + name lookup."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(def ^:dynamic *graph* nil)


(defn- graph-fixture
  [t]
  (exec/with-clean-registry
    #(let [graph (setup/bootstrap-crud-graph-from-golden!)
           storage (:storage graph)]
       (try
         (binding [*graph* graph]
           (t))
         (finally (sp/close storage))))))


(use-fixtures :once
  (setup/create-container-fixture)
  graph-fixture)


(defn- find-fn-usages
  "Invoke `:find-fn-usages` via the executor with `fn-id` as the free
   arg — same path the production `:_delete-secret-usages` reaches."
  [fn-id]
  (let [{:keys [ctx all-name->id storage]} *graph*
        target-id (get all-name->id :find-fn-usages)]
    (when-not target-id
      (throw (ex-info "find-fn-usages fn-id missing — bootstrap regression"
                      {:type :test/missing-fn-id})))
    (exec/execute-with-named-args
      ctx target-id
      (setup/inject-storage-query ctx storage target-id {:fn-id fn-id}))))


(deftest nil-fn-id-short-circuits-to-empty-test
  (testing "nil fn-id → `[]` without running the 5 storage queries"
    (let [result (find-fn-usages nil)]
      (is (= [] result)))))


(deftest parent-reference-found-test
  (testing "fn whose :parent-ids contains the target shows up as :parent"
    (let [storage (:storage *graph*)
          target (setup/create-base-fn! storage (str "target-parent-" (random-uuid)) :int)
          owner (setup/create-composed-fn! storage (str "owner-parent-" (random-uuid)) (:id target))
          result (find-fn-usages (:id target))]
      (is (= 1 (count result)))
      (is (= (:id owner) (:fn-id (first result))))
      (is (= :parent (:reason (first result)))))))


(deftest binding-ref-reference-found-test
  (testing "fn with a binding.ref-fn-id pointing at the target shows up as :binding"
    (let [storage (:storage *graph*)
          target (setup/create-base-fn! storage (str "target-binding-" (random-uuid)) :int)
          host-base (setup/create-base-fn! storage (str "host-base-" (random-uuid)) :int)
          slot (setup/create-slot! storage "input" :int)
          _ (setup/attach-slot! storage (:id host-base) (:id slot) 0)
          host (setup/create-composed-fn! storage (str "host-binding-" (random-uuid)) (:id host-base))
          _ (setup/bind-ref! storage (:id host) (:id slot) (:id target))
          result (find-fn-usages (:id target))]
      (is (= 1 (count result)))
      (is (= (:id host) (:fn-id (first result))))
      (is (= :binding (:reason (first result)))))))


(deftest parent-wins-when-fn-references-via-multiple-paths-test
  (testing "binding < list-item < parent precedence — `:merge` last-wins puts parent on top"
    ;; If the same owner fn references the target both as a parent AND
    ;; via a binding, the merged reasons map records `:parent` (parent
    ;; comes last in the `:_find-fn-usages-reasons-merged :maps` list).
    (let [storage (:storage *graph*)
          target (setup/create-base-fn! storage (str "target-multi-" (random-uuid)) :int)
          host (setup/create-composed-fn! storage (str "owner-multi-" (random-uuid)) (:id target))
          slot (setup/create-slot! storage "input" :int)
          _ (setup/attach-slot! storage (:id target) (:id slot) 0)
          _ (setup/bind-ref! storage (:id host) (:id slot) (:id target))
          result (find-fn-usages (:id target))]
      ;; One row, reason = :parent (highest-precedence source)
      (is (= 1 (count result)))
      (is (= (:id host) (:fn-id (first result))))
      (is (= :parent (:reason (first result)))))))


(deftest no-references-returns-empty-test
  (testing "fn with no incoming references → `[]`"
    (let [storage (:storage *graph*)
          target (setup/create-base-fn! storage (str "target-lonely-" (random-uuid)) :int)
          result (find-fn-usages (:id target))]
      (is (= [] result)))))


(deftest name-is-resolved-test
  (testing "result rows include the owning fn's `:name`"
    (let [storage (:storage *graph*)
          owner-name (str "owner-name-" (random-uuid))
          target (setup/create-base-fn! storage (str "target-name-" (random-uuid)) :int)
          _owner (setup/create-composed-fn! storage owner-name (:id target))
          result (find-fn-usages (:id target))]
      (is (= 1 (count result)))
      (is (= owner-name (:name (first result)))))))
