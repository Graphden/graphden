(ns graphden.packages.tenancy-admin.grants-test
  "The tenancy-admin grants base-fns (migrated out of app.admin via the
   route-collection seam, PLATFORM_PLAN §6). The impls.clj is loaded by the
   package loader (load-file), not the classpath, so we load it the same way
   and exercise the impls over a fake storage."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as sp]))


(def ^:private impls-path "resources/packages/tenancy-admin/grants/impls.clj")


(defn- grant-storage
  [rows]
  (reify sp/StorageCRUD
    (query-entities
      [_ entity-name _where]
      (when (= entity-name :grant) rows))

    (query-entities [_ _ _ _] nil)

    (create-entity [_ _ _] nil)

    (read-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(defn- capturing-storage
  [sink]
  (reify sp/StorageCRUD
    (create-entity [_ entity-name data] (reset! sink [entity-name data]) data)

    (query-entities [_ _ _] nil)

    (query-entities [_ _ _ _] nil)

    (read-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(deftest list-grants-queries-the-grant-entity
  (load-file impls-path)
  (let [list-grants (resolve 'graphden.packages.tenancy-admin.grants.impls/list-grants)]
    (testing "the impl loads (same path the package loader uses)"
      (is (some? list-grants)))
    (testing "it returns the storage's :grant rows"
      (let [rows [{:subject "alice" :capability "write" :namespace "acme"}
                  {:subject "bob" :capability "read" :namespace "shared"}]]
        (is (= rows (list-grants {} {:storage (grant-storage rows)})))))))


(deftest create-grant-writes-a-grant-entity
  (load-file impls-path)
  (let [create-grant (resolve 'graphden.packages.tenancy-admin.grants.impls/create-grant)
        sink (atom nil)]
    (testing "the impl creates a :grant from the three fields"
      (create-grant {:subject "carol" :capability "admin" :namespace "ops"}
                    {:storage (capturing-storage sink)})
      (is (= [:grant {:subject "carol" :capability "admin" :namespace "ops"}] @sink)))))


(deftest create-grant-rejects-unknown-capability
  (load-file impls-path)
  (let [create-grant (resolve 'graphden.packages.tenancy-admin.grants.impls/create-grant)
        sink (atom nil)]
    (testing "an out-of-vocabulary capability is rejected at write — no silently-dead grant"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Unknown capability"
            (create-grant {:subject "eve" :capability "notacap" :namespace "acme"}
                          {:storage (capturing-storage sink)})))
      (is (nil? @sink) "nothing was written for the invalid capability"))
    (testing "a valid (previously-undocumented) capability still writes"
      (create-grant {:subject "carol" :capability "bind-args" :namespace "ops"}
                    {:storage (capturing-storage sink)})
      (is (= [:grant {:subject "carol" :capability "bind-args" :namespace "ops"}] @sink)))))
