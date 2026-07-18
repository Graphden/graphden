(ns graphden.packages.tenancy-admin.grants-test
  "`:list-grants` / `:create-grant` are pure graph compositions now
   (grants/fns.edn) — exercised end-to-end in
   `graphden.integration.faas-app-test` (`create-grant-fn-def-validates-
   and-writes`, the grants panel partial). What remains here is the one
   Clojure impl left in the module: `:grant-capability-set`, the
   boundary encoding of the closed vocabulary the graph validates
   against. The impls.clj is loaded by the package loader (load-file),
   not the classpath, so we load it the same way."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.tenancy.grant :as grant]))


(def ^:private impls-path "resources/packages/tenancy-admin/grants/impls.clj")


(deftest grant-capability-set-encodes-the-vocabulary
  (load-file impls-path)
  (let [cap-set @(resolve 'graphden.packages.tenancy-admin.grants.impls/grant-capability-set)
        encoded (cap-set {} nil)]
    (testing "every capability keyword appears as a truthy name-string key"
      (is (= (into {} (map (fn [c] [(name c) true])) grant/capabilities)
             encoded))
      (is (contains? encoded "admin"))
      (is (not (contains? encoded "notacap"))))
    (testing "the encoding tracks the source vocabulary — adding a capability can't silently desync"
      (is (= (count grant/capabilities) (count encoded))))))
