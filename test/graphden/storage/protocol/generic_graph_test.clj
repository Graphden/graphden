(ns graphden.storage.protocol.generic-graph-test
  "Tests for `graphden.storage.protocol.generic-graph` — the reusable
   StorageCRUD-driven `ExecutionGraph` resolution that backends
   without an optimised implementation fall back on."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.generic-graph :as gg]
    [graphden.storage.protocol.graph :as graph]))


(use-fixtures :once (setup/create-container-fixture))


(deftest resolve-execution-graph-test
  (testing "an unknown fn-id throws :not-found"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ex (try (gg/resolve-execution-graph storage (random-uuid))
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :not-found (:type (ex-data ex)))))
        (finally (sp/close storage)))))

  (testing "resolution pulls the whole closure — parent + ref target"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base   (setup/create-base-fn! storage "gg-base")
              slot   (setup/create-slot! storage "x" :int)
              _      (setup/attach-slot! storage (:id base) (:id slot) 0)
              target (setup/create-base-fn! storage "gg-target")
              composed (setup/create-composed-fn! storage "gg-composed" (:id base))
              _      (setup/bind-ref! storage (:id composed) (:id slot) (:id target))
              result (gg/resolve-execution-graph storage (:id composed))]
          (is (graph/execution-graph? result))
          (let [fn-ids (set (keys (graph/get-graph-fns result)))]
            (is (contains? fn-ids (:id composed)) "the root fn")
            (is (contains? fn-ids (:id base))     "its parent")
            (is (contains? fn-ids (:id target))   "the ref-binding target")))
        (finally (sp/close storage))))))
