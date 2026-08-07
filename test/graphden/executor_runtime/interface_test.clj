(ns ^:integration graphden.executor-runtime.interface-test
  "Boot the system through executor-runtime/interface.clj public API.

   Exercises `rt/start!` / `rt/restart!` / `rt/stop!` against a real
   container-backed system — which also covers the interface→core
   delegation for all three (the mock-based delegation deftests that
   used to sit here asserted the same wiring a second time, each one
   paying a clean-DB fixture for a tautology)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor-runtime.interface :as rt]
    [graphden.storage.protocol.postgres-test-helpers :as pth]))


;; =============================================================================
;; Fixtures
;; =============================================================================

(def ^:dynamic *container* nil)

(use-fixtures :once (pth/create-container-fixture #'*container*))
(use-fixtures :each (pth/create-clean-db-fixture #'*container*))


(deftest interface-integration-test
  (let [overrides {:db/postgres (pth/get-container-config *container*)}]
    (testing "start! via interface returns running system"
      (let [system (rt/start! :test overrides)]
        (try
          (is (map? system))
          (is (some? (:db/schema system)))
          (is (some? (:db/postgres system)))
          (finally
            (rt/stop!)))))

    (testing "restart! via interface restarts system"
      (let [system1 (rt/start! :test overrides)]
        (try
          (is (some? system1))
          (let [system2 (rt/restart! :test overrides)]
            (is (some? system2))
            (is (not= system1 system2)))
          (finally
            (rt/stop!)))))))
