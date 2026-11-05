(ns ^:serial graphden.crud.graph-index-facts-test
  "Index facts conceal before graph composition and retain bounded acquisition.
   Serial because the view-impl seam and reverse-index probe are global vars."
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.crud.entities.list :as entity-list]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tctx]
    [graphden.types.diagnostics :as diag]))


(deftest scoped-concealed-index-facts-test
  (let [own (random-uuid)
        foreign (random-uuid)
        parent (random-uuid)
        reads (atom 0)
        graph {:fns [{:id own :name "own" :org-id "index-owner" :parent-ids [parent]}
                     {:id foreign :name "foreign" :org-id "other" :parent-ids []}]
               :slots [] :fn-slots [] :bindings [] :list-items []}
        unexpected (fn [op] (throw (ex-info "Unexpected index storage call" {:op op})))
        storage (reify sp/StorageCRUD
                  (query-entities
                    [_ entity where]
                    (is (= [:ns {}] [entity where]))
                    (swap! reads inc)
                    [])

                  (query-entities [_ _ _ _] (unexpected :query-entities-opts))

                  (create-entity [_ _ _] (unexpected :create-entity))

                  (read-entity [_ _ _] (unexpected :read-entity))

                  (update-entity [_ _ _ _] (unexpected :update-entity))

                  (delete-entity [_ _ _] (unexpected :delete-entity))

                  (query-latest-per-group [_ _ _ _] (unexpected :query-latest-per-group)))
        ctx {:storage storage :graph-cache (atom graph)}]
    (with-redefs-fn
      {#'entity-list/view-impl-filter (atom #(entity-list/strip-impl-of % #{own}))
       (ns-resolve 'graphden.crud.entities.list 'reverse-ref-index)
       (fn [_] (throw (ex-info "Index must not compute reverse references" {})))}
      (fn []
        (binding [diag/*diagnostics-override* (atom {})]
          (tctx/with-org "index-owner"
                         (let [facts (entity-list/graph-index-facts ctx)]
                           (is (= #{:fns :namespaces} (set (keys facts))))
                           (is (= [own] (mapv :id (:fns facts))))
                           (is (= [] (:parent-ids (first (:fns facts)))))
                           (is (= :composed (:role (first (:fns facts)))))
                           (is (= 1 @reads))
                           (is (= graph @(:graph-cache ctx))))))))))
