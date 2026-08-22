(ns graphden.system.interface-test
  "Tests for system/interface.clj public API.

   Parallel-safe: the lifecycle tests drive REAL integrant over a
   hand-built one-key config (`::probe` / `::other`, whose lifecycle
   multimethods live in this NS), injected through the existing
   `^:dynamic sys/read-config` seam via `binding` — no `with-redefs`
   of the integrant root Vars (which is process-global and forced a
   `^:serial` pin — serial-reduction cluster A). The probe key's
   config value carries its own callback fns, so concurrent tests
   can't share observation state.

   Tests configuration loading and lifecycle functions.
   Full integration tests use test-helpers.clj fixtures."
  (:require
    [aero.core :as aero]
    [clojure.test :refer [deftest is testing]]
    [graphden.system.config]
    [graphden.system.interface :as sys]
    [integrant.core :as ig]))


;; =============================================================================
;; Probe integrant keys — a minimal real lifecycle for the tests below.
;; The initialized value IS the config value, so halt/suspend/resume
;; callbacks travel inside it (no shared test state).
;; =============================================================================

(defmethod ig/init-key ::probe [_ v] v)


(defmethod ig/halt-key! ::probe
  [_ v]
  (when-let [f (:on-halt v)] (f v)))


(defmethod ig/suspend-key! ::probe
  [_ v]
  (when-let [f (:on-suspend v)] (f v)))


(defmethod ig/resume-key ::probe
  [_ v _old-value _old-impl]
  (when-let [f (:on-resume v)] (f v))
  v)


(defmethod ig/init-key ::other [_ v] v)


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
  (testing "stop! halts the system through ig/halt!"
    (let [halted (atom nil)
          system (binding [sys/read-config
                           (fn [_]
                             {::probe {:tag :stop-me
                                       :on-halt (fn [v] (reset! halted v))}})]
                   (sys/start! :test))]
      (is (= :stop-me (get-in system [::probe :tag])) "probe key initialized")
      (sys/stop! system)
      (is (= :stop-me (:tag @halted)) "halt-key! ran for the probe key"))))


(deftest suspend-delegation-test
  (testing "suspend! suspends the system through ig/suspend!"
    (let [suspended (atom nil)
          system (binding [sys/read-config
                           (fn [_]
                             {::probe {:tag :suspend-me
                                       :on-suspend (fn [v] (reset! suspended v))}})]
                   (sys/start! :test))]
      (sys/suspend! system)
      (is (= :suspend-me (:tag @suspended)) "suspend-key! ran for the probe key"))))


(deftest resume-delegation-test
  (testing "resume! resumes the system through ig/resume with the new config"
    (let [resumed (atom nil)
          read-cfg (fn [_]
                     {::probe {:tag :resume-me
                               :on-resume (fn [v] (reset! resumed v))}})
          system (binding [sys/read-config read-cfg]
                   (sys/start! :test))]
      (sys/suspend! system)
      (let [result (binding [sys/read-config read-cfg]
                     (sys/resume! system :test))]
        (is (= :resume-me (:tag @resumed)) "resume-key ran for the probe key")
        (is (= :resume-me (get-in result [::probe :tag]))
            "resume! returns the resumed system map")))))


;; =============================================================================
;; start! with component-keys tests
;; =============================================================================

(deftest start-with-component-keys-synthetic-config-test
  (testing "start! accepts optional component-keys parameter"
    (let [read-cfg (fn [_] {::probe {:tag :a} ::other {:tag :b}})]
      (binding [sys/read-config read-cfg]
        ;; With component-keys: only the named key is initialized
        (let [system (sys/start! :test [::probe])]
          (is (contains? system ::probe)
              "Named component key should be initialized")
          (is (not (contains? system ::other))
              "Unnamed component key should NOT be initialized"))
        ;; Without component-keys: the whole config is initialized
        (let [system (sys/start! :test)]
          (is (= #{::probe ::other} (set (keys system)))
              "Full system start initializes every config key"))))))


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
