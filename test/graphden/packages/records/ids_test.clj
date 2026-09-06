(ns graphden.packages.records.ids-test
  "Anonymous fn identity is per ORG on a tenant path and unchanged on the
   platform tier — the ids package sync has always produced."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.records.ids :as ids]
    [graphden.tenancy.context :as tc]))


(deftest anonymous-fn-id-is-per-org
  (let [h (ids/shape-hash {:a :int})
        platform (ids/anonymous-fn-id h)]
    (testing "the platform tier (unbound, or the public org) keeps the shape-only id"
      (is (= platform (tc/with-org tc/public-org (ids/anonymous-fn-id h))))
      (is (= platform (ids/anonymous-fn-id h)) "deterministic"))
    (testing "a tenant's id differs from the platform's and from another tenant's"
      (let [acme (tc/with-org "acme" (ids/anonymous-fn-id h))
            beta (tc/with-org "beta" (ids/anonymous-fn-id h))]
        (is (not= platform acme))
        (is (not= acme beta))
        (is (= acme (tc/with-org "acme" (ids/anonymous-fn-id h))) "stable within the org")))))
