(ns graphden.services.service-scope-test
  "Unit tests for the service-execution sandbox seam (`cr/run-service-scoped`
   + `cr/service-execution-scope`, task #6). The reconciler runs every
   service start through `run-service-scoped`; the tenancy addon installs a
   seam that, for a tenant service (`:org-id` set, non-public), binds the
   org's effect gate around the start. These tests exercise the seam CONTRACT
   (passthrough when uninstalled, delegation when installed, effect gating)
   and — composed with the `:future` binding conveyance — prove the gate
   crosses into a worker thread the service spawns, which is the whole point:
   a tenant service can't escape its plan's effect sandbox by backgrounding
   its work.

   The `:future` impl is slurp+eval'd via the loader's private
   `load-module-impls`, exactly like `concurrency_test`, so the real
   `future-fn` runs without a normal require."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]))


(def ^:dynamic *future-impl* nil)


(use-fixtures :once
  (fn [f]
    (let [impls ((requiring-resolve 'graphden.packages.loader/load-module-impls)
                 "core" "concurrency")
          entry (:future impls)]
      (binding [*future-impl* (if (map? entry) (:impl entry) entry)]
        (f)))))


(use-fixtures :each
  (fn [f]
    ;; The seam is a process-global atom; keep tests isolated and never leak
    ;; an installed seam into a sibling test or the rest of the suite.
    (reset! cr/service-execution-scope nil)
    (try (f) (finally (reset! cr/service-execution-scope nil)))))


(deftest run-service-scoped-is-passthrough-when-no-seam-installed-test
  (testing "single-tenant / no-addon: the thunk runs directly, unrestricted"
    (is (nil? @cr/service-execution-scope))
    (is (= :ran (cr/run-service-scoped {:org-id "acme"} (constantly :ran))))
    ;; No gate in scope → a forbidden effect is NOT blocked (platform semantics)
    (is (nil? (cr/run-service-scoped {} (fn [] (cr/record-effect! :network)))))))


(deftest run-service-scoped-delegates-svc-and-thunk-to-the-installed-seam-test
  (testing "the seam receives the full svc row + the start thunk, and its
            return value is the run-service-scoped result"
    (let [seen (atom nil)]
      (reset! cr/service-execution-scope
              (fn [svc thunk] (reset! seen svc) (thunk)))
      (is (= :ok (cr/run-service-scoped {:org-id "acme" :id "s1"} (constantly :ok))))
      (is (= {:org-id "acme" :id "s1"} @seen)))))


(deftest installed-seam-gates-the-effects-of-a-service-start-test
  (testing "a seam that restricts effects to #{:db} makes a service start that
            records :network throw :execution/forbidden-effect — the sandbox"
    (reset! cr/service-execution-scope
            (fn [_svc thunk] (binding [cr/*allowed-effects* #{:db}] (thunk))))
    (let [thrown (try (cr/run-service-scoped {:org-id "acme"}
                                             (fn [] (cr/record-effect! :network)))
                      :no-throw
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))]
      (is (= :execution/forbidden-effect thrown)))))


(deftest seam-that-throws-propagates-fail-closed-test
  (testing "a misconfigured seam that throws must NOT silently fall back to
            running the service unsandboxed — the throw propagates"
    (reset! cr/service-execution-scope
            (fn [_svc _thunk] (throw (ex-info "bad install" {:type :test/boom}))))
    (let [ran (atom false)]
      (is (thrown? clojure.lang.ExceptionInfo
            (cr/run-service-scoped {:org-id "acme"}
                                   (fn [] (reset! ran true)))))
      (is (false? @ran) "the start thunk never ran under a broken seam"))))


(deftest seam-effect-gate-crosses-into-a-future-the-service-spawns-test
  (testing "task #6 end to end: a service whose start spawns a background
            worker via the real `future-fn` — the worker inherits the seam's
            effect gate (via the conveyance registry), so a forbidden effect
            in the WORKER throws instead of escaping the sandbox"
    ;; :process is allowed (so the service may spawn a :future) but :network is
    ;; not — exactly a free-ish plan that permits background work but not egress.
    (reset! cr/service-execution-scope
            (fn [_svc thunk] (binding [cr/*allowed-effects* #{:db :process}] (thunk))))
    (let [worker-result (promise)
          worker-body (fn []
                        (deliver worker-result
                                 (try (cr/record-effect! :network) :no-throw
                                      (catch clojure.lang.ExceptionInfo e
                                        (:type (ex-data e))))))
          stopper (cr/run-service-scoped {:org-id "acme"}
                                         (fn [] (*future-impl* {:body (delay worker-body)} nil)))]
      (is (= :execution/forbidden-effect (deref worker-result 2000 :timeout))
          "the org's effect gate reached the worker thread the service spawned")
      (when (fn? stopper) (stopper)))))


(deftest platform-service-future-stays-unrestricted-through-the-seam-test
  (testing "a platform service (the seam runs it unrestricted) whose worker
            records :network is NOT gated — the platform's own background work
            keeps its full effects"
    (reset! cr/service-execution-scope
            (fn [svc thunk]
              ;; Mirror the addon: public / nil org-id → passthrough.
              (if (:org-id svc)
                (binding [cr/*allowed-effects* #{:db}] (thunk))
                (thunk))))
    (let [worker-result (promise)
          worker-body (fn []
                        (deliver worker-result
                                 (try (cr/record-effect! :network) :allowed
                                      (catch Exception _ :threw))))
          stopper (cr/run-service-scoped {:id "platform-svc"}
                                         (fn [] (*future-impl* {:body (delay worker-body)} nil)))]
      (is (= :allowed (deref worker-result 2000 :timeout))
          "no org-id → seam passthrough → worker unrestricted")
      (when (fn? stopper) (stopper)))))
