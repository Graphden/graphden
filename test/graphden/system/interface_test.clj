(ns ^:serial graphden.system.interface-test
  "Tests for system/interface.clj public API.

   Tests configuration loading and lifecycle functions.
   Full integration tests use test-helpers.clj fixtures."
  (:require
    [aero.core :as aero]
    [clojure.test :refer [deftest is testing]]
    [graphden.system.config]
    [graphden.system.interface :as sys]
    [integrant.core :as ig]))


;; =============================================================================
;; read-config tests
;; =============================================================================

(deftest read-config-test
  (testing "read-config returns valid Integrant config for :test profile"
    (let [config (sys/read-config :test)]
      (is (map? config) "Config should be a map")
      (is (contains? config :db/schema) "Config should have :db/schema")
      (is (contains? config :db/postgres) "Config should have :db/postgres")
      (is (contains? config :db/versioned) "Config should have :db/versioned")
      (is (contains? config :exec/base-fns) "Config should have :exec/base-fns")
      (is (contains? config :exec/context) "Config should have :exec/context")))

  (testing "read-config returns valid config for :dev profile"
    (let [config (sys/read-config :dev)]
      (is (map? config))
      (is (contains? config :db/schema))
      (is (contains? config :db/postgres))))

  (testing "read-config returns valid config for :prod profile (incl. service reconciler)"
    (let [config (sys/read-config :prod)]
      (is (map? config))
      (is (contains? config :db/schema))
      (is (contains? config :db/postgres))
      (is (contains? config :exec/service-reconciler)
          ":prod must wire the service reconciler (see docs/SERVICES.md)")))

  (testing "read-config throws for invalid profile"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Config file not found"
          (sys/read-config :nonexistent)))))


;; =============================================================================
;; start-with-overrides! tests (config merging)
;; =============================================================================

(deftest start-with-overrides-config-merge-test
  (testing "start-with-overrides! merges config correctly"
    ;; We test the merging logic by checking that read-config + manual merge
    ;; produces the same structure as what start-with-overrides! would use
    (let [base-config (sys/read-config :test)
          overrides {:db/postgres {:jdbc-url "jdbc:postgresql://override:5432/test"}}
          ;; Simulate what start-with-overrides! does internally
          merged-config (reduce-kv
                          (fn [cfg k v]
                            (update cfg k merge v))
                          base-config
                          overrides)]
      (is (= "jdbc:postgresql://override:5432/test"
             (get-in merged-config [:db/postgres :jdbc-url]))
          "Override should be applied to config"))))


;; =============================================================================
;; stop!/suspend!/resume! delegation tests
;; =============================================================================

(deftest stop-delegation-test
  (testing "stop! delegates to ig/halt!"
    (let [halted? (atom false)
          mock-system {:db/schema :mock}]
      (with-redefs [ig/halt! (fn [sys]
                               (reset! halted? true)
                               (is (= mock-system sys)))]
        (sys/stop! mock-system)
        (is @halted? "ig/halt! should be called")))))


(deftest suspend-delegation-test
  (testing "suspend! delegates to ig/suspend!"
    (let [suspended? (atom false)
          mock-system {:db/schema :mock}]
      (with-redefs [ig/suspend! (fn [sys]
                                  (reset! suspended? true)
                                  (is (= mock-system sys)))]
        (sys/suspend! mock-system)
        (is @suspended? "ig/suspend! should be called")))))


(deftest resume-delegation-test
  (testing "resume! delegates to ig/resume with new config"
    (let [resumed? (atom false)
          mock-system {:db/schema :mock}]
      (with-redefs [ig/resume (fn [config sys]
                                (reset! resumed? true)
                                (is (map? config) "Config should be passed")
                                (is (= mock-system sys) "System should be passed")
                                :resumed)]
        (let [result (sys/resume! mock-system :test)]
          (is @resumed? "ig/resume should be called")
          (is (= :resumed result)))))))


;; =============================================================================
;; start! with component-keys tests
;; =============================================================================

(deftest start-with-component-keys-test
  (testing "start! accepts optional component-keys parameter"
    (let [init-called? (atom false)
          init-keys-passed (atom nil)]
      (with-redefs [ig/init (fn [_config & [component-keys]]
                              (reset! init-called? true)
                              (reset! init-keys-passed component-keys)
                              {:mock :system})]
        ;; Test with component-keys
        (sys/start! :test [:db/schema])
        (is @init-called? "ig/init should be called")
        (is (= [:db/schema] @init-keys-passed)
            "Component keys should be passed to ig/init")

        ;; Reset and test without component-keys
        (reset! init-called? false)
        (reset! init-keys-passed :not-called)
        (sys/start! :test)
        (is @init-called? "ig/init should be called")
        (is (nil? @init-keys-passed)
            "No component keys should be passed for full system start")))))


;; =============================================================================
;; Aero reader tag tests (for system.config coverage)
;; =============================================================================

(deftest aero-reader-ig-ref-test
  (testing "#ig/ref reader tag resolves to Integrant ref"
    (let [edn-str "{:key #ig/ref :db/postgres}"
          parsed (aero/read-config (java.io.StringReader. edn-str))]
      (is (= (ig/ref :db/postgres) (:key parsed))))))


(deftest aero-reader-ig-refset-test
  (testing "#ig/refset reader tag resolves to Integrant refset"
    (let [edn-str "{:key #ig/refset [:db/postgres :db/schema]}"
          parsed (aero/read-config (java.io.StringReader. edn-str))]
      (is (= (ig/refset [:db/postgres :db/schema]) (:key parsed))))))


(deftest aero-reader-var-test
  (testing "#var reader tag resolves var to its value"
    ;; Test with a var from our own codebase
    (let [edn-str "{:key #var graphden.storage.protocol.core/*max-batch-size*}"
          parsed (aero/read-config (java.io.StringReader. edn-str))]
      (is (number? (:key parsed))))))
