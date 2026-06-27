(ns graphden.tenancy.addon-test
  "The tenancy addon fragment splices OrgScopedStorage into the storage
   stack via the manifest (PLATFORM_PLAN §3.0 B3)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.system.config :as config]
    [graphden.tenancy.addon]
    [graphden.tenancy.storage :as ts]
    [integrant.core :as ig]))


(deftest fragment-redirects-versioned-base-through-decorator
  (let [cfg (config/read-config :test ["graphden/tenancy/addon.edn"])]
    (testing "addon adds :org/scoped-storage wrapping the :app/storage seam"
      (is (= (ig/ref :app/storage) (:base (:org/scoped-storage cfg)))))
    (testing ":db/versioned's base is redirected through the decorator"
      (is (= (ig/ref :org/scoped-storage) (:base-storage (:db/versioned cfg)))
          "stack becomes Versioned(OrgScoped(app/storage(Postgres)))"))
    (testing "deep-merge preserves the core :app/storage seam unchanged"
      (is (= (ig/ref :db/postgres) (:base (:app/storage cfg)))))))


(deftest init-key-builds-org-scoped-storage
  (testing ":org/scoped-storage init-key wraps its base in the decorator"
    (let [s (ig/init-key :org/scoped-storage {:base ::stub-base})]
      (is (instance? graphden.tenancy.storage.OrgScopedStorage s))
      (is (= ::stub-base (:base s)))
      (is (= ts/default-scoped-entities (:scoped? s)) "defaults to the graph entities")))
  (testing "an explicit scoped-entities set is honoured"
    (let [s (ig/init-key :org/scoped-storage {:base ::stub-base :scoped-entities [:fn]})]
      (is (= #{:fn} (:scoped? s))))))
