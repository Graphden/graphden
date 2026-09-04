(ns ^:integration graphden.crud.lint-graph-test
  "The graph lint over a LIVE branch, end to end through storage: two
   fn-defs written through the CRUD layer that duplicate each other show
   up in `lint.graph/lint-branch`; a suppression entry shaped the way the
   Lint panel stores it (`lint-suppressions` const, `[{:rule :fn-ids}]`)
   hides the finding; a third copy changes the key and brings it back.

   The unit tests pin the translation over hand-built rows; this is the
   only place the real snapshot (`cached-or-load-graph`, org-sliced,
   branch-resolved) and the real `packages.owned` predicate are
   exercised together."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.types-api :as types-api]
    [graphden.executor.test-setup :as setup]
    [graphden.lint.core :as lint]
    [graphden.lint.graph :as lg]
    [graphden.storage.protocol.core :as sp]))


(def ^:dynamic *graph* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (fn [f]
    (let [graph (setup/bootstrap-crud-graph-from-golden!*
                  "graphden.crud.lint-graph-test"
                  ["core" "web" "app"])]
      (binding [*graph* graph]
        (try (f) (finally (setup/close-graph! graph)))))))


(defn- fn-id-by-name
  [storage nm]
  (:id (first (sp/query-entities storage :fn {:name nm}))))


(defn- slot-id
  [storage fn-id slot-name]
  (let [slot-ids (map :slot-id (sp/query-entities storage :fn-slot {:fn-id fn-id}))
        slots (sp/read-entities storage :slot (vec slot-ids))]
    (some (fn [[id s]] (when (= slot-name (:name s)) id)) slots)))


(defn- make-assoc-child!
  "A child of :assoc binding map/key literally and value to `value-ref`
   — three bound values, above the warning weight. `key` distinguishes
   the shapes the two tests build, so neither test's fns join the
   other's duplicate group (test order is randomised)."
  [ctx storage nm key value-ref]
  (let [assoc-id (fn-id-by-name storage "assoc")
        row (entities/create-entity "fn" {:name nm :parent-ids [assoc-id]} ctx)
        fid (:id row)]
    (entities/create-entity "binding" {:fn-id fid :slot-id (slot-id storage assoc-id "map")
                                       :value {:class "x"} :value-present true} ctx)
    (entities/create-entity "binding" {:fn-id fid :slot-id (slot-id storage assoc-id "key")
                                       :value key :value-present true} ctx)
    (entities/create-entity "binding" {:fn-id fid :slot-id (slot-id storage assoc-id "value")
                                       :ref-fn-id value-ref} ctx)
    fid))


(defn- findings-naming
  [ctx fid suppress]
  (filterv #(some #{fid} (:fn-ids %)) (lg/lint-branch ctx suppress)))


(deftest duplicate-definition-through-storage-test
  (let [{:keys [ctx storage]} *graph*
        title (fn-id-by-name storage "const")
        a (make-assoc-child! ctx storage "lint-probe-a" "title" title)
        b (make-assoc-child! ctx storage "lint-probe-b" "title" title)]
    (testing "two structurally identical user fn-defs are one duplicate-definition warning"
      (let [[f :as fs] (findings-naming ctx a #{})]
        (is (= 1 (count fs)))
        (is (= :duplicate-definition (:rule f)))
        ;; ids ride in :fns order (name-sorted), not uuid-sorted — the editor
        ;; zips the two
        (is (= [a b] (:fn-ids f)))
        ;; root-namespace rows carry the empty namespace in the live graph
        (is (= #{["" :lint-probe-a] ["" :lint-probe-b]} (set (:fns f))))))
    (testing "the per-ctx snapshot the lint reads agrees with a storage read"
      (is (= (into #{} (map :id) (:fns (types-api/cached-or-load-graph ctx)))
             (into #{} (map :id) (:fns (types-api/load-graph-entities-uncached storage))))))
    (testing "the platform's own fn-defs raise nothing (the corpus gate keeps them clean)"
      (is (empty? (remove (fn [f] (some #{a b} (:fn-ids f))) (lg/lint-branch ctx #{})))))
    (testing "the entry the Lint panel stores hides the finding"
      (let [[f] (findings-naming ctx a #{})
            stored {:rule "duplicate-definition" :fn-ids (mapv str (:fn-ids f))}
            key [(keyword (:rule stored)) (vec (sort (:fn-ids stored)))]]
        (is (= (lint/finding-key f) key))
        (is (empty? (findings-naming ctx a #{key})))
        (testing "a third copy is a new key — the old suppression no longer applies"
          (let [c (make-assoc-child! ctx storage "lint-probe-c" "title" title)
                [g] (findings-naming ctx a #{key})]
            (is (some? g))
            (is (= [a b c] (:fn-ids g)))))))))


(deftest unreferenced-private-through-storage-test
  (let [{:keys [ctx storage]} *graph*
        title (fn-id-by-name storage "const")
        dead (make-assoc-child! ctx storage "_lint-probe-dead" "other" title)]
    (testing "a user `_`-private nothing references is a warning"
      (let [[f] (findings-naming ctx dead #{})]
        (is (= :unreferenced-private (:rule f)))))
    (testing "referencing it from another fn clears the finding"
      (let [assoc-id (fn-id-by-name storage "assoc")
            user (:id (entities/create-entity "fn" {:name "lint-probe-user" :parent-ids [assoc-id]} ctx))]
        (entities/create-entity "binding" {:fn-id user :slot-id (slot-id storage assoc-id "map")
                                           :ref-fn-id dead} ctx)
        (is (empty? (filterv #(= :unreferenced-private (:rule %)) (findings-naming ctx dead #{}))))))))
