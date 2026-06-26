(ns graphden.executor.effect-gating-test
  "Runtime effect gate (PLATFORM_PLAN §5) — a context with
   `:allowed-effects` makes `record-effect!` throw
   `:execution/forbidden-effect` for any effect outside the set. The
   cloud sandbox boundary: env / io / network excluded for cloud orgs,
   unrestricted (nil) for self-hosted."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]))


(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (fn [t]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!)]
      (t))))


(deftest env-effect-gate
  (let [{:keys [ctx all-name->id]} *bootstrap*
        env-id (get all-name->id :env)
        run (fn [c]
              (exec/execute-with-named-args c env-id
                                            {:name "GRAPHDEN_NONEXISTENT_VAR_XYZ"}))]
    (testing "unrestricted ctx (no :allowed-effects) — :env runs, no gate"
      (is (nil? (run ctx)) "reads a missing env var → nil, must not throw"))
    (testing "restricted ctx without :env — forbidden-effect thrown before the read"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Forbidden effect: :env"
            (run (assoc ctx :allowed-effects #{:db :time})))))
    (testing "the thrown ex carries the canonical type + offending effect"
      (let [e (try (run (assoc ctx :allowed-effects #{:db}))
                   (catch clojure.lang.ExceptionInfo ex (ex-data ex)))]
        (is (= :execution/forbidden-effect (:type e)))
        (is (= :env (:effect e)))
        (is (= #{:db} (:allowed e)))))
    (testing "restricted ctx that DOES allow :env — runs"
      (is (nil? (run (assoc ctx :allowed-effects #{:env})))))))
