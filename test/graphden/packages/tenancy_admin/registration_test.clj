(ns graphden.packages.tenancy-admin.registration-test
  "The tenancy-admin registration base-fns (create-org / set-org-handler),
   migrated out of app.admin via the route-collection seam (§6). The impls.clj
   is loaded by the package loader (load-file), not the classpath, so we load
   it the same way and exercise the impls over a fake storage."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]))


(def ^:private impls-path "resources/packages/tenancy-admin/registration/impls.clj")


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


(deftest create-org-registers-an-org-entity
  (load-file impls-path)
  (let [create-org (resolve 'graphden.packages.tenancy-admin.registration.impls/create-org)
        sink (atom nil)]
    (testing "the impl creates an :org from its name (= slug = subdomain)"
      (create-org {:name "acme"} {:storage (capturing-storage sink)})
      (is (= [:org {:name "acme"}] @sink)))))


(deftest set-org-handler-points-org-at-its-handler-fn
  (load-file impls-path)
  (let [set-org-handler (resolve 'graphden.packages.tenancy-admin.registration.impls/set-org-handler)
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


(deftest set-org-execution-mode-flips-mode-and-drops-the-memo
  (load-file impls-path)
  (let [set-mode (resolve 'graphden.packages.tenancy-admin.registration.impls/set-org-execution-mode)
        updated (atom nil)
        org-storage (fn [name->id]
                      (reify sp/StorageCRUD
                        (query-entities
                          [_ en where]
                          (when (= en :org)
                            (when-let [id (get name->id (:name where))]
                              [{:id id :name (:name where)}])))

                        (query-entities [_ _ _ _] nil)

                        (update-entity [_ en id data] (reset! updated [en id data]) data)

                        (create-entity [_ _ _] nil)

                        (read-entity [_ _ _] nil)

                        (delete-entity [_ _ _] nil)

                        (query-latest-per-group [_ _ _ _] nil)))]
    (testing "flips :execution-mode on the org found by name"
      (set-mode {:name "acme" :execution-mode "byo"} {:storage (org-storage {"acme" "org-1"})})
      (is (= [:org "org-1" {:execution-mode "byo"}] @updated)))
    (testing "drops the byo memo so the flip is effective immediately"
      (tc/invalidate-byo-cache!)
      ;; Populate a stale HOSTED memo for acme.
      (is (false? (tc/byo-org? (org-storage {}) "acme")))
      ;; Flip → set-mode calls invalidate-byo-cache! for acme.
      (set-mode {:name "acme" :execution-mode "byo"} {:storage (org-storage {"acme" "org-1"})})
      ;; A fresh read (memo dropped) sees the byo row.
      (is (true? (tc/byo-org?
                   (reify sp/StorageCRUD
                     (query-entities [_ _ _] [{:name "acme" :execution-mode "byo"}])

                     (query-entities [_ _ _ _] nil)

                     (create-entity [_ _ _] nil)

                     (read-entity [_ _ _] nil)

                     (update-entity [_ _ _ _] nil)

                     (delete-entity [_ _ _] nil)

                     (query-latest-per-group [_ _ _ _] nil))
                   "acme"))))
    (testing "a missing org throws (loud feedback for a bad slug), no write"
      (reset! updated nil)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No org named"
            (set-mode {:name "ghost" :execution-mode "byo"} {:storage (org-storage {})})))
      (is (nil? @updated)))
    (tc/invalidate-byo-cache!)))
