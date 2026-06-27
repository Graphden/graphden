(ns graphden.tenancy.app-router-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.app-router :as app]
    [graphden.tenancy.subdomain :as subdomain]))


(defn- org-storage
  "Fake storage: `query-entities :org {:name n}` → the row for n (with its
   handler-fn-id, possibly nil) when present, else []."
  [name->handler]
  (reify sp/StorageCRUD
    (query-entities
      [_ en where]
      (when (= en :org)
        (let [n (:name where)]
          (when (contains? name->handler n)
            [{:name n :handler-fn-id (get name->handler n)}]))))

    (query-entities [_ _ _ _] nil)

    (create-entity [_ _ _] nil)

    (read-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(defn- req
  [host]
  {:headers {"host" host}})


(deftest app-handler-target-test
  (let [handler-id (random-uuid)
        storage (org-storage {"acme" handler-id "beta" nil})
        resolver (subdomain/identity-org-resolver)]
    (testing "tenant subdomain → its org + handler"
      (is (= {:org "acme" :handler-fn-id handler-id}
             (app/app-handler-target storage (req "acme.graphden.app") resolver "graphden.app" nil))))
    (testing "org exists but no handler configured → nil handler-fn-id (→ 404)"
      (is (= {:org "beta" :handler-fn-id nil}
             (app/app-handler-target storage (req "beta.graphden.app") resolver "graphden.app" nil))))
    (testing "apex (no subdomain) → nil → not an app request"
      (is (nil? (app/app-handler-target storage (req "graphden.app") resolver "graphden.app" nil))))
    (testing "unknown subdomain → org named but no row → nil handler-fn-id"
      (is (= {:org "ghost" :handler-fn-id nil}
             (app/app-handler-target storage (req "ghost.graphden.app") resolver "graphden.app" nil))))))


(deftest make-app-router-non-execution-paths
  (let [handler-id (random-uuid)
        storage (org-storage {"acme" handler-id "beta" nil})
        ar (app/make-app-router (subdomain/identity-org-resolver) "graphden.app" nil)
        ctx {:storage storage}]
    (testing "apex → nil → dispatch falls through to editor/API"
      (is (nil? (ar ctx (req "graphden.app")))))
    (testing "org with no handler → 404 (it's an app request, don't fall through)"
      (is (= 404 (:status (ar ctx (req "beta.graphden.app"))))))))
