(ns ^:integration graphden.versioning.storage.collision-parity-test
  "Parity guard for the write-path resolved-view-collision drift class.

   The singular (`create-entity`/`update-entity`) and batch
   (`create-entities`/`update-entities`) versioned-write paths must BOTH reject
   the same per-branch resolved-view collisions — a duplicate live
   `(namespace-id, name)` fn, or two list-items at the same `(binding-id,
   position)`. The batch path once ran ONLY the list-item check and no fn-name
   check (fixed 2026-08-25: it now shares `batch-collision-guard!`); this matrix
   pins that every write path stays in lock-step, so a future refactor that
   drops a check on one path reddens CI here rather than corrupting the graph."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.versioning.storage.core :as vs]))


(def ^:dynamic *container* nil)
(use-fixtures :once (th/create-container-fixture #'*container*))
(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(defn- versioned
  []
  (let [schema (-> (mds/create-builder) (gds/extend-builder) (vds/extend-builder)
                   (vts/extend-builder) (ds/build))]
    (vs/wrap-with-versioning
      (-> (pg/create-storage (th/get-container-config *container*))
          (sp/initialize-with-cleanup! schema)))))


(defn- err-type
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))


(deftest fn-name-collision-rejected-on-every-write-path
  ;; A duplicate live (namespace-id, name) fn must be rejected whether the write
  ;; goes through the singular OR the batch path, on create OR update (rename).
  (let [v (versioned)]
    (try
      (testing "singular create"
        (sp/create-entity v :fn {:name "s-create" :parent-ids []})
        (is (= :constraint-violation/fn-name-collision
               (err-type #(sp/create-entity v :fn {:name "s-create" :parent-ids []})))))
      (testing "singular update (rename onto a live name)"
        (sp/create-entity v :fn {:name "s-upd-a" :parent-ids []})
        (let [b (sp/create-entity v :fn {:name "s-upd-b" :parent-ids []})]
          (is (= :constraint-violation/fn-name-collision
                 (err-type #(sp/update-entity v :fn (:id b) {:name "s-upd-a"}))))))
      (testing "batch create"
        (sp/create-entity v :fn {:name "b-create" :parent-ids []})
        (is (= :constraint-violation/fn-name-collision
               (err-type #(sp/create-entities v :fn [{:id (random-uuid)
                                                      :name "b-create" :parent-ids []}])))))
      (testing "batch update (rename onto a live name)"
        (sp/create-entity v :fn {:name "b-upd-a" :parent-ids []})
        (let [b (sp/create-entity v :fn {:name "b-upd-b" :parent-ids []})]
          (is (= :constraint-violation/fn-name-collision
                 (err-type #(sp/update-entities v :fn [{:id (:id b) :name "b-upd-a"}]))))))
      (finally (sp/close v)))))


(deftest list-item-position-collision-rejected-on-every-write-path
  (let [v (versioned)]
    (try
      (let [f (sp/create-entity v :fn {:name "li-fn" :parent-ids []})
            t (sp/create-entity v :fn {:name "li-type" :parent-ids []})
            slot (sp/create-entity v :slot {:name "li-slot" :type-fn-id (:id t)})
            bnd (sp/create-entity v :binding {:fn-id (:id f) :slot-id (:id slot) :list-append true})
            bid (:id bnd)]
        (sp/create-entity v :binding-list-item {:binding-id bid :position 0 :value "x"})
        (testing "singular create at an occupied position"
          (is (= :constraint-violation/position-collision
                 (err-type #(sp/create-entity v :binding-list-item
                                              {:binding-id bid :position 0 :value "y"})))))
        (testing "batch create at an occupied position"
          (is (= :constraint-violation/position-collision
                 (err-type #(sp/create-entities v :binding-list-item
                                                [{:id (random-uuid) :binding-id bid
                                                  :position 0 :value "z"}])))))
        (testing "batch create with an intra-batch duplicate position"
          (is (= :constraint-violation/position-collision
                 (err-type #(sp/create-entities v :binding-list-item
                                                [{:id (random-uuid) :binding-id bid :position 5 :value "a"}
                                                 {:id (random-uuid) :binding-id bid :position 5 :value "b"}]))))))
      (finally (sp/close v)))))
