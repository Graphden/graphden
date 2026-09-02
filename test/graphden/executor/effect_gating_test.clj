(ns graphden.executor.effect-gating-test
  "Runtime effect gate (docs/TENANCY_SEAM.md § Effect gate) — a context with
   `:allowed-effects` makes `record-effect!` throw
   `:execution/forbidden-effect` for any effect outside the set. The
   cloud sandbox boundary: env / io / network excluded for cloud orgs,
   unrestricted (nil) for self-hosted."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.system.deploy-config :as deploy-config]))


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


(deftest deploy-config-reads-under-any-gate
  ;; The counterpart of `env-effect-gate`: a PUBLIC deployment setting is
  ;; read from the boot snapshot with NO effect recorded, so the editor's
  ;; own partials render for a tenant request that the gate keeps away
  ;; from `:env`. Only declared keys exist — nothing else is reachable.
  (let [{:keys [ctx all-name->id]} *bootstrap*
        dc-id (get all-name->id :deploy-config)
        run (fn [c k] (exec/execute-with-named-args c dc-id {:key k}))]
    (try
      (deploy-config/install! {:hub-url "https://hub.example"})
      (testing "the strict request-level cloud set (no :env) still reads a declared key"
        (is (= "https://hub.example"
               (run (assoc ctx :allowed-effects cr/cloud-request-allowed-effects) :hub-url))))
      (testing "the plan-level set (the tenant's own graph) reads it too"
        (is (= "https://hub.example"
               (run (assoc ctx :allowed-effects cr/default-cloud-allowed-effects) :hub-url))))
      (testing "an undeclared key is nil, not an env read — under the same gate"
        (is (nil? (run (assoc ctx :allowed-effects cr/default-cloud-allowed-effects)
                       :GRAPHDEN_ALERT_TELEGRAM_TOKEN))))
      (finally (deploy-config/clear!)))))


(deftest raw-sql-effect-gate
  ;; The cloud-sandbox hole this closes: raw `:pg-query` rides `:db`
  ;; (cloud-ALLOWED) to the platform pool, bypassing org-scope + RLS.
  ;; `:pg-query` records `:db` THEN `:raw-sql`; under a cloud ctx `:db`
  ;; passes the gate and `:raw-sql` trips it, so a tenant graph can't
  ;; read/mutate the platform DB (incl. RLS-less token/user/grant).
  (let [{:keys [ctx all-name->id]} *bootstrap*
        pg-query-id (get all-name->id :pg-query)
        run (fn [c]
              (exec/execute-with-named-args c pg-query-id
                                            {:hsql {:select [1]}}))]
    (testing ":raw-sql is a recognised, cloud-forbidden effect"
      (is (contains? cr/known-effects :raw-sql))
      (is (contains? cr/cloud-forbidden-effects :raw-sql)))
    (testing "the safe org-scoped path stays allowed — :db is NOT forbidden"
      (is (contains? cr/default-cloud-allowed-effects :db))
      (is (not (contains? cr/default-cloud-allowed-effects :raw-sql))))
    (testing "cloud ctx allows :db yet gates raw :pg-query on :raw-sql"
      (let [e (try (run (assoc ctx :allowed-effects cr/default-cloud-allowed-effects))
                   (catch clojure.lang.ExceptionInfo ex (ex-data ex)))]
        (is (= :execution/forbidden-effect (:type e)))
        (is (= :raw-sql (:effect e))
            "gate must trip on :raw-sql, not :db — proving :db-alone wouldn't block it")))))
