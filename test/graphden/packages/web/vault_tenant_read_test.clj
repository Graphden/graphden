(ns graphden.packages.web.vault-tenant-read-test
  "A tenant's bound secret is readable in its own RESTRICTED execution.
   A secret binding is a `:vault-get` resolver binding; the executor runs
   `:vault-get` at arg-resolution time, inside the tenant's effect-gated
   execution. The raw vault ops are operator-only there — except
   `:vault-get` on the tenant's own `org/<org-id>/` prefix, which the
   client confines (docs/SECRETS.md § Per-org vault paths). Vault HTTP is
   replaced through the thread-local `vault/*impl-override*` seam."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.clients.vault :as vault]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.tenancy.context :as tctx]))


(use-fixtures :once (setup/create-container-fixture))


(def ^:private paid-tier-effects
  "A tenant execution's allow-list on a plan that grants `:network`."
  (conj cr/default-cloud-allowed-effects :network))


(def ^:private fn-defs
  [{:name :vtr-own :namespace "vtrtest" :parent :secret-leaf
    :args {:in {:resolver :vault-get :value "org/acme/db/password"}}}
   {:name :vtr-foreign :namespace "vtrtest" :parent :secret-leaf
    :args {:in {:resolver :vault-get :value "org/other/db/password"}}}])


(defn- root-type
  "The `:type` of the innermost ExceptionInfo in `e`'s cause chain."
  [e]
  (->> (iterate ex-cause e)
       (take-while some?)
       (keep (comp :type ex-data))
       last))


(defn- run
  "Execute vtrtest/`fn-name` under `org` (nil = platform tier) with
   `allowed` effects; the fake vault answers any path with its own name.
   Returns the result, or the innermost error `:type`."
  [ctx org allowed fn-name]
  (binding [vault/*impl-override* {:get-secret (fn [_client path] (str "value@" path))}]
    (try
      (tctx/with-org (or org tctx/public-org)
                     (exec/execute-with-named-args
                       (cond-> (assoc ctx :vault {:address "http://stub" :token "t"})
                         allowed (assoc :allowed-effects allowed))
                       (records/fn-id "vtrtest" fn-name) {}))
      (catch Exception e (root-type e)))))


(deftest tenant-reads-its-own-bound-secret-in-a-restricted-execution
  (let [{:keys [ctx storage]} (setup/bootstrap-crud-graph-from-golden!
                                "graphden.packages.web.vault-tenant-read-test")]
    (setup/sync-and-invalidate! ctx storage fn-defs)
    (testing "a binding under the tenant's own prefix resolves"
      (is (= "value@org/acme/db/password" (run ctx "acme" paid-tier-effects :vtr-own))))
    (testing "a binding under another org's prefix is refused by the client (403)"
      (is (= :vault/path-forbidden (run ctx "acme" paid-tier-effects :vtr-foreign))))
    (testing "a restricted execution on the platform tier stays operator-only"
      (is (= :vault/operator-only (run ctx nil paid-tier-effects :vtr-own))))
    (testing "an unrestricted (operator) execution reads any path, as before"
      (is (= "value@org/other/db/password" (run ctx nil nil :vtr-foreign))))))
