(ns ^:integration graphden.tenancy.row-cap-test
  "Per-org fn row-cap (task #7 stage-b) against real Postgres — the `fn-count`
   raw query, the plan-driven `over-entity-quota?`, and the OrgScopedStorage
   `create-entity` gate that rejects a tenant `:fn` create over the cap while
   leaving the public org and non-`:fn` writes untouched."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.packages.schema :as pkgs]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.services.schema :as svcs]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.org-schema :as org-schema]
    [graphden.tenancy.plan :as plan]
    [graphden.tenancy.storage :as ts]))


(use-fixtures :once (setup/create-container-fixture))


(defn- build-schema
  []
  (-> (mds/create-builder)
      (gds/extend-builder)
      (vts/extend-builder)
      (vds/extend-builder)
      (es/extend-builder)
      (svcs/extend-builder)
      (pkgs/extend-builder)
      (org-schema/extend-builder)
      (ds/build)))


(deftest row-cap-counts-and-gates-fn-creates
  (pth/clean-database-fast! setup/*container*)
  (let [storage (pg/create-storage (pth/get-container-config setup/*container*))
        scoped (ts/org-scoped-storage storage)]
    (sp/initialize storage (build-schema))
    ;; Seed two fns for tenant "acme" through the org-scoped decorator (stamps
    ;; org_id = "acme"); the quota seam is nil here, so nothing gates yet.
    (binding [tc/*current-org* "acme"]
      (sp/create-entity scoped :fn {:name "acme-a"})
      (sp/create-entity scoped :fn {:name "acme-b"}))

    (testing "fn-count is a raw org-scoped count(*)"
      (is (= 2 (plan/fn-count storage "acme")))
      (is (zero? (plan/fn-count storage "other"))))

    (testing "over-entity-quota? is false well under the free-tier cap"
      ;; no :org row → the free-tier default plan (cap 500); 2 ≪ 500.
      (is (false? (plan/over-entity-quota? storage "acme"))))

    (testing "create-entity rejects a tenant :fn over the cap, but not the public org or a non-:fn write"
      (let [saved @ts/entity-quota-exceeded?]
        (try
          ;; Seam says ONLY "acme" is over.
          (reset! ts/entity-quota-exceeded? (fn [org] (= org "acme")))
          (binding [tc/*current-org* "acme"]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"plan's function limit"
                  (sp/create-entity scoped :fn {:name "acme-c"})))
            (is (= 2 (plan/fn-count storage "acme")) "the rejected create wrote nothing")
            ;; a non-:fn write by the capped org is NOT gated
            (is (some? (sp/create-entity scoped :ns {:name "acme-ns"}))))
          ;; the public / platform org is never capped
          (binding [tc/*current-org* tc/public-org]
            (is (some? (sp/create-entity scoped :fn {:name "public-fn"}))))
          (finally (reset! ts/entity-quota-exceeded? saved)))))

    (sp/close storage)))
