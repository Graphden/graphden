(ns graphden.versioning.identity-repair-test
  "Unit tests for the identity-plane repair primitives, driven through
   an in-memory map-backed StorageCRUD stub (the loud-stub pattern from
   `storage.protocol.traits-seed-test`: every method a primitive must
   not touch fails loud). The real-DB path stays integration-covered by
   `graphden.system.moved-identity-test`; this NS pins the pure
   mechanics — the exact `ref-fields` surface, owner attribution,
   parent-ids vector fill, dry-run/plan! collection, and the purge
   cascade — at unit speed."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.identity-repair :as idr]))


(defn- unexpected!
  [method]
  (throw (AssertionError. (str method " must not run in this test"))))


(defn- row-matches?
  [row where]
  (every? (fn [[k v]] (= v (get row k))) where))


(defn- mem-storage
  "In-memory StorageCRUD over `db`, an atom of `{entity → {id → row}}`.
   Only the methods the repair primitives use are implemented
   (query-entities/3, update-entity, delete-entity) — the rest throw."
  [db]
  (reify sp/StorageCRUD
    (create-entity [_ _ _] (unexpected! "create-entity"))

    (read-entity [_ _ _] (unexpected! "read-entity"))

    (update-entity
      [_ entity-name id data]
      (swap! db update-in [entity-name id] merge data)
      nil)

    (delete-entity
      [_ entity-name id]
      (swap! db update entity-name dissoc id)
      nil)

    (query-entities
      [_ entity-name where]
      (filterv #(row-matches? % where) (vals (get @db entity-name))))

    (query-entities [_ _ _ _] (unexpected! "query-entities/4"))

    (query-latest-per-group [_ _ _ _] (unexpected! "query-latest-per-group"))))


(deftest base-of-test
  (testing "unwraps a VersionedStorage-shaped map to its base"
    (is (= :inner (idr/base-of {:base-storage :inner}))))

  (testing "a bare storage passes through unchanged"
    (let [storage (mem-storage (atom {}))]
      (is (identical? storage (idr/base-of storage))))))


;; === repoint-refs! ===

(defn- repoint-seed
  "One row per field of the exact ref surface `repoint-refs!` fills,
   plus control rows that must stay untouched."
  []
  {:binding {:b1 {:id :b1 :fn-id :f1 :ref-fn-id :old}
             :b2 {:id :b2 :fn-id :f1 :type-override-fn-id :old :ref-fn-id :other}
             :b3 {:id :b3 :fn-id :f2 :resolver-fn-id :old}}
   :binding-list-item {:i1 {:id :i1 :binding-id :b1 :ref-fn-id :old}
                       :i2 {:id :i2 :binding-id :b1 :ref-fn-id :other}}
   :binding-version {:bv1 {:id :bv1 :binding-id :b1 :ref-fn-id :old}}
   :binding-list-item-version {:iv1 {:id :iv1 :binding-id :b1 :ref-fn-id :old}}
   :slot {:s1 {:id :s1 :type-fn-id :old}
          :s2 {:id :s2 :type-fn-id :other}}
   :fn {:f1 {:id :f1 :parent-ids [:p1 :old] :base-fn-id :old}
        :f2 {:id :f2 :parent-ids [] :return-type-fn-id :old :element-fn-id :old}
        :f3 {:id :f3 :parent-ids [:p1]}}})


(deftest repoint-refs!-fills-whole-ref-surface
  (let [db (atom (repoint-seed))
        n (idr/repoint-refs! (mem-storage db) {:old :new})]
    (testing "counts one touch per filled field (parent-ids = one touch)"
      ;; b1 b2 b3 + i1 + bv1 + iv1 + s1 = 7; f1 base-fn-id + parent-ids,
      ;; f2 return-type-fn-id + element-fn-id = 4 more.
      (is (= 11 n)))

    (testing "every ref field across both planes now targets the new id"
      (is (= :new (get-in @db [:binding :b1 :ref-fn-id])))
      (is (= :new (get-in @db [:binding :b2 :type-override-fn-id])))
      (is (= :new (get-in @db [:binding :b3 :resolver-fn-id])))
      (is (= :new (get-in @db [:binding-list-item :i1 :ref-fn-id])))
      (is (= :new (get-in @db [:binding-version :bv1 :ref-fn-id])))
      (is (= :new (get-in @db [:binding-list-item-version :iv1 :ref-fn-id])))
      (is (= :new (get-in @db [:slot :s1 :type-fn-id]))))

    (testing "fn type-FKs are filled and parent-ids keeps order + other parents"
      (is (= :new (get-in @db [:fn :f1 :base-fn-id])))
      (is (= [:p1 :new] (get-in @db [:fn :f1 :parent-ids])))
      (is (= :new (get-in @db [:fn :f2 :return-type-fn-id])))
      (is (= :new (get-in @db [:fn :f2 :element-fn-id]))))

    (testing "rows not referencing the old id are untouched"
      (is (= :other (get-in @db [:binding :b2 :ref-fn-id])))
      (is (= :other (get-in @db [:binding-list-item :i2 :ref-fn-id])))
      (is (= :other (get-in @db [:slot :s2 :type-fn-id])))
      (is (= [:p1] (get-in @db [:fn :f3 :parent-ids]))))))


(deftest repoint-refs!-dry-run-plans-without-writing
  (let [seed (repoint-seed)
        db (atom seed)
        plan (atom [])
        n (idr/repoint-refs! (mem-storage db) {:old :new} #(swap! plan conj %) true)]
    (testing "returns the would-touch count and plans every change"
      (is (= 11 n))
      (is (= 11 (count @plan)))
      (is (every? #(= :repoint (:op %)) @plan)))

    (testing "the parent-ids plan entry carries the full vector fill"
      (is (some #(= % {:op :repoint :entity :fn :id :f1 :field :parent-ids
                       :from [:p1 :old] :to [:p1 :new]})
                @plan)))

    (testing "a scalar plan entry carries entity/id/field/from/to"
      (is (some #(= % {:op :repoint :entity :binding :id :b1 :field :ref-fn-id
                       :from :old :to :new})
                @plan)))

    (testing "dry-run writes nothing"
      (is (= seed @db)))))


;; === inbound-refs ===

(deftest inbound-refs-reports-external-refs-only
  (let [db (atom {:binding {:bx {:id :bx :fn-id :x :ref-fn-id :x}
                            :bo {:id :bo :fn-id :other}
                            :be {:id :be :fn-id :other :ref-fn-id :x}}
                  :binding-list-item {:ix {:id :ix :binding-id :bx :ref-fn-id :x}
                                      :ie {:id :ie :binding-id :bo :ref-fn-id :x}}
                  :binding-version {:bvx {:id :bvx :fn-id :x :ref-fn-id :x}
                                    :bve {:id :bve :fn-id :other :type-override-fn-id :x}}
                  :binding-list-item-version {:ivx {:id :ivx :binding-id :bx :ref-fn-id :x}}
                  :slot {:sx {:id :sx :type-fn-id :x}}
                  :fn {:x {:id :x :parent-ids [] :base-fn-id :x}
                       :d {:id :d :parent-ids [:x] :return-type-fn-id :x}}})]
    (testing "owned rows (x's own bindings/items/versions + x's own fn row)
              are skipped; external refs and shared slots are reported"
      (is (= #{{:entity :binding :id :be :field :ref-fn-id}
               {:entity :binding-list-item :id :ie :field :ref-fn-id}
               {:entity :binding-version :id :bve :field :type-override-fn-id}
               {:entity :slot :id :sx :field :type-fn-id}
               {:entity :fn :id :d :field :return-type-fn-id}
               {:entity :fn :id :d :field :parent-ids}}
             (set (idr/inbound-refs (mem-storage db) :x)))))))


(deftest inbound-refs-empty-for-unreferenced-fn
  (let [db (atom {:binding {:by {:id :by :fn-id :y :ref-fn-id :z}}
                  :fn {:y {:id :y :parent-ids []}
                       :z {:id :z :parent-ids []}}})]
    (is (= [] (idr/inbound-refs (mem-storage db) :y)))))


;; === inbound-refs-many ===

(deftest inbound-refs-many-attributes-owners
  (let [db (atom {:binding {:cb {:id :cb :fn-id :c :ref-fn-id :a}
                            :ab {:id :ab :fn-id :a :ref-fn-id :b}
                            :aa {:id :aa :fn-id :a :ref-fn-id :a}}
                  :binding-list-item {:ci {:id :ci :binding-id :cb :ref-fn-id :a}}
                  :binding-version {:avb {:id :avb :fn-id :a}}
                  :binding-list-item-version {:aiv {:id :aiv :binding-id :avb :ref-fn-id :b}}
                  :slot {:sa {:id :sa :type-fn-id :a}}
                  :fn {:a {:id :a :parent-ids []}
                       :b {:id :b :parent-ids []}
                       :c {:id :c :parent-ids []}
                       :d {:id :d :parent-ids [:a :a] :base-fn-id :b}}})
        hits (idr/inbound-refs-many (mem-storage db) #{:a :b :z})]
    (testing "ids with no inbound refs are absent from the map"
      (is (= #{:a :b} (set (keys hits)))))

    (testing ":a — external + internal refs with owners; a self-ref
              (owner == target) is excluded; a shared slot owner is nil;
              duplicate parent-ids collapse to one hit"
      (is (= #{{:entity :binding :id :cb :field :ref-fn-id :owner-fn-id :c}
               {:entity :binding-list-item :id :ci :field :ref-fn-id :owner-fn-id :c}
               {:entity :slot :id :sa :field :type-fn-id :owner-fn-id nil}
               {:entity :fn :id :d :field :parent-ids :owner-fn-id :d}}
             (set (:a hits)))))

    (testing ":b — internal ref owner is in the target set (caller can
              classify); a version-plane binding owner resolves list-item
              versions too"
      (is (= #{{:entity :binding :id :ab :field :ref-fn-id :owner-fn-id :a}
               {:entity :binding-list-item-version :id :aiv :field :ref-fn-id
                :owner-fn-id :a}
               {:entity :fn :id :d :field :base-fn-id :owner-fn-id :d}}
             (set (:b hits)))))))


;; === purge-fn-subgraph! ===

(defn- purge-seed
  []
  {:fn {:x {:id :x :parent-ids []}
        :keep {:id :keep :parent-ids []}}
   :fn-version {:fv1 {:id :fv1 :fn-id :x}
                :fvk {:id :fvk :fn-id :keep}}
   :binding {:b1 {:id :b1 :fn-id :x}
             :b2 {:id :b2 :fn-id :x}
             :bk {:id :bk :fn-id :keep}}
   :binding-version {:bv1 {:id :bv1 :binding-id :b1}
                     :bvk {:id :bvk :binding-id :bk}}
   :binding-list-item {:i1 {:id :i1 :binding-id :b1}
                       :ik {:id :ik :binding-id :bk}}
   :binding-list-item-version {:iv1 {:id :iv1 :binding-id :b1}
                               :ivk {:id :ivk :binding-id :bk}}
   :fn-slot {:fs1 {:id :fs1 :fn-id :x}
             :fsk {:id :fsk :fn-id :keep}}
   :fn-slot-version {:fsv1 {:id :fsv1 :fn-slot-id :fs1}
                     :fsvk {:id :fsvk :fn-slot-id :fsk}}})


(deftest purge-fn-subgraph!-removes-owned-rows-only
  (let [db (atom (purge-seed))
        n (idr/purge-fn-subgraph! (mem-storage db) :x)]
    (testing "counts every removed row (2 bindings + item + 3 version rows
              + fn-slot + fn-version + the fn row)"
      (is (= 9 n)))

    (testing "the whole owned subgraph is gone; other fns' rows survive"
      (is (= {:fn {:keep {:id :keep :parent-ids []}}
              :fn-version {:fvk {:id :fvk :fn-id :keep}}
              :binding {:bk {:id :bk :fn-id :keep}}
              :binding-version {:bvk {:id :bvk :binding-id :bk}}
              :binding-list-item {:ik {:id :ik :binding-id :bk}}
              :binding-list-item-version {:ivk {:id :ivk :binding-id :bk}}
              :fn-slot {:fsk {:id :fsk :fn-id :keep}}
              :fn-slot-version {:fsvk {:id :fsvk :fn-slot-id :fsk}}}
             @db)))))


(deftest purge-fn-subgraph!-dry-run-plans-without-deleting
  (let [seed (purge-seed)
        db (atom seed)
        plan (atom [])
        n (idr/purge-fn-subgraph! (mem-storage db) :x #(swap! plan conj %) true)]
    (testing "returns the would-remove count and plans every removal"
      (is (= 9 n))
      (is (= 9 (count @plan)))
      (is (every? #(= :remove (:op %)) @plan))
      (is (some #(= % {:op :remove :entity :fn :id :x}) @plan)))

    (testing "dry-run deletes nothing"
      (is (= seed @db)))))
