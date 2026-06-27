(ns graphden.packages.app.admin-test
  "The app.admin grants base-fns. The impls.clj is loaded by the package
   loader (load-file), not the classpath, so we load it the same way and
   exercise the impls over a fake storage."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as sp]))


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


(deftest list-grants-queries-the-grant-entity
  (load-file "resources/packages/app/admin/impls.clj")
  (let [list-grants (resolve 'graphden.packages.app.admin.impls/list-grants)]
    (testing "the impl loads (same path the package loader uses)"
      (is (some? list-grants)))
    (testing "it returns the storage's :grant rows"
      (let [rows [{:subject "alice" :capability "write" :namespace "acme"}
                  {:subject "bob" :capability "read" :namespace "shared"}]]
        (is (= rows (list-grants {} {:storage (grant-storage rows)})))))))


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


(deftest create-grant-writes-a-grant-entity
  (load-file "resources/packages/app/admin/impls.clj")
  (let [create-grant (resolve 'graphden.packages.app.admin.impls/create-grant)
        sink (atom nil)]
    (testing "the impl creates a :grant from the three fields"
      (create-grant {:subject "carol" :capability "admin" :namespace "ops"}
                    {:storage (capturing-storage sink)})
      (is (= [:grant {:subject "carol" :capability "admin" :namespace "ops"}] @sink)))))


(deftest create-org-registers-an-org-entity
  (load-file "resources/packages/app/admin/impls.clj")
  (let [create-org (resolve 'graphden.packages.app.admin.impls/create-org)
        sink (atom nil)]
    (testing "the impl creates an :org from its name (= slug = subdomain)"
      (create-org {:name "acme"} {:storage (capturing-storage sink)})
      (is (= [:org {:name "acme"}] @sink)))))


(deftest invoke-set-org-handler-calls-the-ctx-seam
  (load-file "resources/packages/app/admin/impls.clj")
  (let [invoke (resolve 'graphden.packages.app.admin.impls/invoke-set-org-handler)
        fid (random-uuid)
        called (atom nil)
        ctx {:set-org-handler (fn [_c fnid] (reset! called fnid) :ok)}]
    (testing "calls the injected seam with the parsed uuid"
      (is (= :ok (invoke {:fn-id (str fid)} ctx)))
      (is (= fid @called)))
    (testing "no seam (single-tenant / no addon) → nil"
      (is (nil? (invoke {:fn-id (str fid)} {}))))))


(deftest set-org-handler-points-org-at-its-handler-fn
  (load-file "resources/packages/app/admin/impls.clj")
  (let [set-org-handler (resolve 'graphden.packages.app.admin.impls/set-org-handler)
        handler-id (random-uuid)
        updated (atom nil)
        storage (reify sp/StorageCRUD
                  (query-entities
                    [_ en where]
                    (when (= en :org) [{:id "org-1" :name (:name where)}]))

                  (query-entities [_ _ _ _] nil)

                  (update-entity [_ en id data] (reset! updated [en id data]) data)

                  (create-entity [_ _ _] nil)

                  (read-entity [_ _ _] nil)

                  (delete-entity [_ _ _] nil)

                  (query-latest-per-group [_ _ _ _] nil))]
    (testing "finds the org by name and sets :handler-fn-id (string → uuid)"
      (set-org-handler {:name "acme" :handler-fn-id (str handler-id)} {:storage storage})
      (is (= [:org "org-1" {:handler-fn-id handler-id}] @updated)))))
