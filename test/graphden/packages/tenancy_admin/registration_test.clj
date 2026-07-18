(ns graphden.packages.tenancy-admin.registration-test
  "The registration provisioning fns (create-org / create-token /
   create-domain / set-org-handler / set-org-execution-mode) are pure
   graph compositions now (fns.edn) — exercised end-to-end in
   `graphden.integration.faas-app-test` (`registration-fn-defs-drive-
   provisioning`, the token round-trip, the domain resolver test).

   What remains here is the one Clojure impl left in the module:
   `:invalidate-byo-cache` must actually drop the org's byo memo so a
   mode flip is visible immediately. The impls.clj is loaded by the
   package loader (load-file), not the classpath, so we load it the
   same way."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]))


(def ^:private impls-path "resources/packages/tenancy-admin/registration/impls.clj")


(defn- org-storage
  "Storage whose :org query answers `rows`."
  [rows]
  (reify sp/StorageCRUD
    (query-entities [_ en _] (when (= en :org) rows))

    (query-entities [_ _ _ _] nil)

    (create-entity [_ _ _] nil)

    (read-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(deftest invalidate-byo-cache-drops-the-memo
  (load-file impls-path)
  (let [invalidate @(resolve 'graphden.packages.tenancy-admin.registration.impls/invalidate-byo-cache)]
    (tc/invalidate-byo-cache!)
    (testing "populate a stale HOSTED memo for acme"
      (is (false? (tc/byo-org? (org-storage []) "acme"))))
    (testing "the base-fn drops acme's memo → a fresh read sees the byo row"
      (invalidate {:name "acme"} nil)
      (is (true? (tc/byo-org?
                   (org-storage [{:name "acme" :execution-mode "byo"}])
                   "acme"))))
    (tc/invalidate-byo-cache!)))
