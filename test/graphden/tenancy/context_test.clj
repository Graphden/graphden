(ns graphden.tenancy.context-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.tenancy.context :as tc]))


(deftest defaults-to-public
  (is (= "public" tc/public-org))
  (is (= "public" (tc/current-org)) "unbound → shared public org (single-tenant)"))


(deftest with-org-binds-dynamically
  (testing "binds the thread-local"
    (is (= "acme" (tc/with-org "acme" (tc/current-org)))))
  (testing "nil org → public (never scopes to a nil tenant)"
    (is (= "public" (tc/with-org nil (tc/current-org)))))
  (testing "scope is restored after the form"
    (tc/with-org "acme" :ignored)
    (is (= "public" (tc/current-org))))
  (testing "nests"
    (is (= "inner"
           (tc/with-org "outer"
                        (tc/with-org "inner" (tc/current-org)))))
    (is (= "outer"
           (tc/with-org "outer"
                        (tc/with-org "inner" (tc/current-org))
                        (tc/current-org))))))


(deftest org-from-principal-defaults-public
  (is (= "acme" (tc/org-from-principal {:org "acme" :authenticated? true})))
  (is (= "public" (tc/org-from-principal {:authenticated? true}))
      "principal without :org (single-token mode) → public")
  (is (= "public" (tc/org-from-principal nil)) "nil principal → public"))
