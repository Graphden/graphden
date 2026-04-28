(ns graphden.system.core-test
  "Tests for Integrant init-key implementations in system.core.

   Covers:
   - :http/server halt-key!, suspend-key!, resume-key
   - :exec/fn-entities init-key
   - Basic lifecycle testing with mocks"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.interface :as exec]
    [integrant.core :as ig]))


;; =============================================================================
;; :http/server Tests (using mock)
;; =============================================================================

(defn- mock-execute-by-name
  "Creates a mock server stop function."
  [_context _fn-name _args]
  ;; Return a function that simulates http-kit server stop
  (let [stopped? (atom false)]
    (fn []
      (reset! stopped? true))))


(deftest http-server-halt-test
  (testing "halt-key! calls server stop function"
    (let [stopped? (atom false)
          mock-server (fn []
                        (reset! stopped? true))]
      ;; Simulate halt behavior
      (ig/halt-key! :http/server mock-server)
      (is @stopped? "Server stop function should be called")))

  (testing "halt-key! handles nil server gracefully"
    ;; Should not throw
    (is (nil? (ig/halt-key! :http/server nil)))))


(deftest http-server-suspend-test
  (testing "suspend-key! calls server stop function"
    (let [stopped? (atom false)
          mock-server (fn []
                        (reset! stopped? true))]
      ;; Simulate suspend behavior
      (ig/suspend-key! :http/server mock-server)
      (is @stopped? "Server stop function should be called")))

  (testing "suspend-key! handles nil server gracefully"
    ;; Should not throw
    (is (nil? (ig/suspend-key! :http/server nil)))))


(deftest http-server-resume-with-mock-test
  (testing "resume-key calls init-key with same config"
    ;; :http/server routes through `cr/execute-by-name` under the default
    ;; `EXECUTOR=compiled` path; redef both that and the legacy entry so
    ;; the test is insensitive to which executor the env var selects.
    (with-redefs [exec/execute-by-name mock-execute-by-name
                  cr/execute-by-name mock-execute-by-name]
      (let [mock-context {:storage :mock :registry :mock}
            mock-packages {:startup-fn :test-server}
            opts {:context mock-context :packages mock-packages :port 8888}
            config {:http/server opts}
            ;; Simulate a previous server
            old-server (fn [] nil)
            ;; Resume should create new server (calls init-key internally)
            new-server (ig/resume-key :http/server opts config old-server)]
        (is (fn? new-server) "Resume should return a server function")))))


(deftest http-server-init-with-mock-test
  (testing "init-key returns a server stop function"
    (with-redefs [exec/execute-by-name mock-execute-by-name
                  cr/execute-by-name mock-execute-by-name]
      (let [mock-context {:storage :mock :registry :mock}
            mock-packages {:startup-fn :test-server}
            opts {:context mock-context :packages mock-packages :port 9999}
            ;; Init should return a function (the stop function)
            result (ig/init-key :http/server opts)]
        (is (fn? result) "init-key should return a function")
        ;; Calling the stop function should not throw
        (when (fn? result)
          (result))))))


;; =============================================================================
;; :exec/fn-entities Tests (using mock)
;; =============================================================================

(defn- mock-sync-fns
  "Mock for sync-fns-to-storage! that returns a map of fns."
  ([_storage fn-defs] (mock-sync-fns _storage fn-defs {}))
  ([_storage fn-defs _ns-id-map]
   ;; Return a map of fn-name -> fn-entity for each fn-def
   (into {}
         (map (fn [fn-def]
                [(:name fn-def) {:id (random-uuid) :name (name (:name fn-def))}])
              fn-defs))))


(deftest fn-entities-init-test
  (testing "init-key creates fn entities from packages"
    (with-redefs [fn-composition/sync-fns-to-storage! mock-sync-fns]
      (let [mock-storage :mock-storage
            mock-packages {:fn-defs [{:name :test-fn :parent :const}
                                     {:name :another-fn :parent :add}]}
            opts {:storage mock-storage :packages mock-packages}
            result (ig/init-key :exec/fn-entities opts)]
        (is (map? result) "Should return a map of fn entities")
        (is (contains? result :test-fn) "Should contain test-fn")
        (is (contains? result :another-fn) "Should contain another-fn"))))

  (testing "init-key handles empty fn-defs"
    (with-redefs [fn-composition/sync-fns-to-storage! mock-sync-fns]
      (let [opts {:storage :mock :packages {:fn-defs []}}
            result (ig/init-key :exec/fn-entities opts)]
        (is (map? result))
        (is (empty? result))))))
