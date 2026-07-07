(ns graphden.tenancy.deploy-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.deploy :as deploy]))


(defn- fake-storage
  "owned-fns = fn-ids the tenant can read (simulating OrgScoped own/public);
   updates = atom recording the :org update."
  [owned-fns updates]
  (reify sp/StorageCRUD
    (read-entity
      [_ en id]
      (when (and (= en :fn) (contains? owned-fns id)) {:id id}))

    (query-entities
      [_ en _where]
      (when (= en :org) [{:id "org-row-1" :name "acme"}]))

    (query-entities [_ _ _ _] nil)

    (create-entity [_ _ _] nil)

    (update-entity [_ en id data] (reset! updates [en id data]) data)

    (delete-entity [_ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(deftest set-org-handler-validates-then-updates
  (let [fid (random-uuid)
        updates (atom nil)
        ctx {:storage (fake-storage #{fid} updates)}]
    (testing "a tenant pointing its own fn at its org → :org row updated"
      (tc/with-org "acme" (deploy/set-org-handler! ctx fid))
      (is (= [:org "org-row-1" {:handler-fn-id fid}] @updates)))
    (testing "public / unauthenticated → :authz/forbidden, no write"
      (reset! updates nil)
      (let [ex (try (tc/with-org tc/public-org (deploy/set-org-handler! ctx fid))
                    nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :authz/forbidden (:type (ex-data ex))))
        (is (nil? @updates))))
    (testing "a fn the tenant can't read (another org's) → forbidden, no write"
      (let [up (atom nil)
            ctx2 {:storage (fake-storage #{} up)}
            ex (try (tc/with-org "acme" (deploy/set-org-handler! ctx2 (random-uuid)))
                    nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :authz/forbidden (:type (ex-data ex))))
        (is (nil? @up))))))


(deftest set-org-handler-missing-org-row-throws
  ;; A valid tenant token whose `:org` row is gone (deleted post-token) must
  ;; throw :authz/forbidden — NOT return nil, which the handler would render as
  ;; a 200 `"nil"` body.
  (let [fid (random-uuid)
        up (atom nil)
        storage (reify sp/StorageCRUD
                  (read-entity [_ en id] (when (and (= en :fn) (= id fid)) {:id id}))
                  (query-entities [_ en _] (when (= en :org) [])) ; org row missing
                  (query-entities [_ _ _ _] nil)
                  (create-entity [_ _ _] nil)
                  (update-entity [_ en id data] (reset! up [en id data]) data)
                  (delete-entity [_ _ _] nil)
                  (query-latest-per-group [_ _ _ _] nil))
        ex (try (tc/with-org "acme" (deploy/set-org-handler! {:storage storage} fid))
                nil (catch clojure.lang.ExceptionInfo e e))]
    (is (= :authz/forbidden (:type (ex-data ex))))
    (is (nil? @up) "no :org write when the row is missing")))
