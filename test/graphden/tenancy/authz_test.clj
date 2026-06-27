(ns graphden.tenancy.authz-test
  "Per-target-namespace write enforcement (PLATFORM_PLAN §4.2 refinement)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.authz :as authz]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.grant :as grant]))


(defn- ns-store
  "Minimal storage exposing `:ns` read-entity from a {ns-id → {:name
   :parent-id}} tree."
  [rows]
  (reify sp/StorageCRUD
    (read-entity [_ entity-name id] (when (= entity-name :ns) (get rows id)))
    (create-entity [_ _ _] nil)
    (update-entity [_ _ _ _] nil)
    (delete-entity [_ _ _] nil)
    (query-entities [_ _ _] nil)
    (query-entities [_ _ _ _] nil)
    (query-latest-per-group [_ _ _ _] nil)))


(def ^:private store
  (ns-store {"acme" {:name "acme" :parent-id nil}
             "team" {:name "team" :parent-id "acme"}}))


(deftest namespace-path-walks-the-ns-tree
  (is (= "acme" (authz/namespace-path store "acme")))
  (is (= "acme.team" (authz/namespace-path store "team")))
  (is (= "" (authz/namespace-path store nil)) "nil → root")
  (is (= "" (authz/namespace-path store "missing")) "unresolvable → root"))


(deftest writable?-checks-grant-against-the-resolved-path
  (let [grants (grant/static-grant-store
                 [{:subject "alice" :capability :write :namespace "acme"}
                  {:subject "bob" :capability :write :namespace "acme.team"}])]
    (testing "a parent-namespace grant covers descendants"
      (is (authz/writable? grants store {:user "alice"} "acme"))
      (is (authz/writable? grants store {:user "alice"} "team")))
    (testing "a sub-namespace grant does NOT cover the parent"
      (is (authz/writable? grants store {:user "bob"} "team"))
      (is (not (authz/writable? grants store {:user "bob"} "acme"))))
    (testing "no :user → denied"
      (is (not (authz/writable? grants store {} "acme"))))))


(deftest authorize-writer-gates-tenant-fn-writes
  (let [grants (grant/static-grant-store
                 [{:subject "bob" :capability :write :namespace "acme.team"}])
        guard (authz/authorize-writer grants store)]
    (tc/with-org "acme"
                 (binding [tc/*current-principal* {:user "bob"}]
                   (testing "write to a granted namespace passes"
                     (is (nil? (guard :fn {:namespace-id "team"}))))
                   (testing "write to an ungranted namespace throws :authz/forbidden"
                     (is (thrown? clojure.lang.ExceptionInfo (guard :fn {:namespace-id "acme"})))
                     (is (= :authz/forbidden
                            (try (guard :fn {:namespace-id "acme"})
                                 (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
                   (testing "non-:fn entity + fn-without-namespace pass (org gate / RLS cover them)"
                     (is (nil? (guard :binding {:namespace-id "acme"})))
                     (is (nil? (guard :fn {:name "x"}))))))
    (testing "platform / admin (public org) is never gated"
      (tc/with-org tc/public-org
                   (binding [tc/*current-principal* {:user "nobody"}]
                     (is (nil? (guard :fn {:namespace-id "acme"}))))))))
