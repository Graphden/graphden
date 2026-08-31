(ns graphden.versioning.storage.uniqueness-test
  "Unit tests for the per-branch RESOLVED-VIEW collision checks, driven
   through an in-memory map-backed storage stub (loud-stub pattern from
   `storage.protocol.traits-seed-test`: every method a check must not
   touch fails loud). The real-SQL write paths stay covered by the
   integration suites (`versioning.storage.core-test`,
   `collision-parity-test`); this NS pins the resolution semantics the
   checks promise — soft-deleted / off-branch rows never collide, the
   batch's own writes overlay the view (position swaps), a merge can
   surface a collision — plus the advisory-lock key builders (the lock
   ACQUISITION itself needs real SQL and is not unit-testable here)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.resolution :as res]
    [graphden.versioning.storage.uniqueness :as uniq]))


(defn- unexpected!
  [method]
  (throw (AssertionError. (str method " must not run in this test"))))


(defn- row-matches?
  "Query-predicate semantics of `query-entities`: a collection value is
   membership, a scalar is equality."
  [row where]
  (every? (fn [[k v]]
            (let [rv (get row k)]
              (if (and (coll? v) (not (map? v)))
                (contains? (set v) rv)
                (= rv v))))
          where))


(defn- latest-row
  [rows]
  (reduce (fn [a b] (if (pos? (compare (:created-at b) (:created-at a))) b a))
          (first rows)
          (rest rows)))


(defn- mem-storage
  "In-memory read-only storage over `db`, a map `{entity → {id → row}}`.
   Implements exactly the read surface the collision checks (and the
   resolution layer under them) use; every write method throws."
  [db]
  (reify
    sp/StorageCRUD

    (create-entity [_ _ _] (unexpected! "create-entity"))

    (read-entity
      [_ entity-name id]
      (get-in db [entity-name id]))

    (update-entity [_ _ _ _] (unexpected! "update-entity"))

    (delete-entity [_ _ _] (unexpected! "delete-entity"))

    (query-entities
      [_ entity-name where]
      (filterv #(row-matches? % where) (vals (get db entity-name))))

    (query-entities [_ _ _ _] (unexpected! "query-entities/4"))

    (query-latest-per-group
      [_ entity-name where group-cols]
      (->> (vals (get db entity-name))
           (filter #(row-matches? % where))
           (group-by (apply juxt group-cols))
           vals
           (mapv latest-row)))


    sp/StorageBatchCRUD

    (create-entities [_ _ _] (unexpected! "create-entities"))

    (read-entities
      [_ entity-name ids]
      (into {}
            (keep (fn [id]
                    (when-let [row (get-in db [entity-name id])]
                      [id row])))
            ids))

    (update-entities [_ _ _] (unexpected! "update-entities"))

    (upsert-entities [_ _ _] (unexpected! "upsert-entities"))

    (delete-entities [_ _ _] (unexpected! "delete-entities"))

    (query-ref-many-owners [_ _ _ _] (unexpected! "query-ref-many-owners"))))


(defn- loud-storage
  "A storage where EVERY method throws — for the short-circuit paths
   that must never touch storage at all."
  []
  (reify
    sp/StorageCRUD

    (create-entity [_ _ _] (unexpected! "create-entity"))

    (read-entity [_ _ _] (unexpected! "read-entity"))

    (update-entity [_ _ _ _] (unexpected! "update-entity"))

    (delete-entity [_ _ _] (unexpected! "delete-entity"))

    (query-entities [_ _ _] (unexpected! "query-entities/3"))

    (query-entities [_ _ _ _] (unexpected! "query-entities/4"))

    (query-latest-per-group [_ _ _ _] (unexpected! "query-latest-per-group"))))


(defn- err-data
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))


(defmacro with-isolated-chain-cache
  "Run `body` against a fresh branch-chain cache so test branch rows
   never leak into (or read from) the process-wide chain cache."
  [& body]
  `(binding [res/*branch-chain-cache* (atom {})]
     (let [res# (do ~@body)]
       res#)))


;; === check-fn-name-collision! ===

(defn- fn-name-db
  "Branch b1 and (unrelated) b2, both roots. On b1's resolved view:
   :f-live holds \"taken\" in :ns1, :f-other-ns holds \"taken\" in :ns2,
   :f-dead once held \"freed\" but is tombstoned, :f-renamed once held
   \"old-name\" but resolves to \"renamed\" now. :f-elsewhere holds
   \"b2-only\" — versioned only on b2."
  []
  {:branch {:b1 {:id :b1}
            :b2 {:id :b2}}
   :fn {:f-live {:id :f-live :parent-ids []}
        :f-dead {:id :f-dead :parent-ids []}
        :f-elsewhere {:id :f-elsewhere :parent-ids []}
        :f-other-ns {:id :f-other-ns :parent-ids []}
        :f-renamed {:id :f-renamed :parent-ids []}}
   :fn-version
   {:v-live {:id :v-live :fn-id :f-live :branch-id :b1
             :name "taken" :namespace-id :ns1 :created-at 1}
    :v-dead-1 {:id :v-dead-1 :fn-id :f-dead :branch-id :b1
               :name "freed" :namespace-id :ns1 :created-at 1}
    :v-dead-2 {:id :v-dead-2 :fn-id :f-dead :branch-id :b1
               :name "freed" :namespace-id :ns1 :created-at 2 :deleted-at 2}
    :v-elsewhere {:id :v-elsewhere :fn-id :f-elsewhere :branch-id :b2
                  :name "b2-only" :namespace-id :ns1 :created-at 1}
    :v-other-ns {:id :v-other-ns :fn-id :f-other-ns :branch-id :b1
                 :name "taken" :namespace-id :ns2 :created-at 1}
    :v-renamed-1 {:id :v-renamed-1 :fn-id :f-renamed :branch-id :b1
                  :name "old-name" :namespace-id :ns1 :created-at 1}
    :v-renamed-2 {:id :v-renamed-2 :fn-id :f-renamed :branch-id :b1
                  :name "renamed" :namespace-id :ns1 :created-at 2}}})


(deftest fn-name-collision-throws-on-live-duplicate
  (with-isolated-chain-cache
    (let [storage (mem-storage (fn-name-db))
          data (err-data #(uniq/check-fn-name-collision!
                            storage :b1 :fn
                            {:id :f-new :name "taken" :namespace-id :ns1}))]
      (is (= :constraint-violation/fn-name-collision (:type data)))
      (is (= [:f-live] (:colliding-fn-ids data)))
      (is (= "taken" (:name data)))
      (is (= :ns1 (:namespace-id data)))
      (is (string? (:reason data))))))


(deftest fn-name-collision-resolved-view-exemptions
  (with-isolated-chain-cache
    (let [storage (mem-storage (fn-name-db))]
      (testing "a soft-deleted (tombstoned) fn no longer blocks its name"
        (is (nil? (uniq/check-fn-name-collision!
                    storage :b1 :fn
                    {:id :f-new :name "freed" :namespace-id :ns1}))))

      (testing "a same-named fn in ANOTHER namespace does not collide"
        (is (nil? (uniq/check-fn-name-collision!
                    storage :b1 :fn
                    {:id :f-new :name "taken" :namespace-id :ns-fresh}))))

      (testing "a name held only on an unrelated branch does not collide here"
        (is (nil? (uniq/check-fn-name-collision!
                    storage :b1 :fn
                    {:id :f-new :name "b2-only" :namespace-id :ns1}))))

      (testing "after a rename the OLD name no longer collides even though
                old version rows still carry it"
        (is (nil? (uniq/check-fn-name-collision!
                    storage :b1 :fn
                    {:id :f-new :name "old-name" :namespace-id :ns1}))))

      (testing "the live holder itself (self-id) is excluded"
        (is (nil? (uniq/check-fn-name-collision!
                    storage :b1 :fn
                    {:id :f-live :name "taken" :namespace-id :ns1})))))))


(deftest fn-name-collision-on-unrelated-branch-detects-its-own-live-row
  (with-isolated-chain-cache
    (let [storage (mem-storage (fn-name-db))
          data (err-data #(uniq/check-fn-name-collision!
                            storage :b2 :fn
                            {:id :f-new :name "b2-only" :namespace-id :ns1}))]
      (is (= :constraint-violation/fn-name-collision (:type data)))
      (is (= [:f-elsewhere] (:colliding-fn-ids data))))))


(deftest fn-name-collision-short-circuits
  (testing "non-:fn entities and anonymous fns never touch storage"
    (is (nil? (uniq/check-fn-name-collision!
                (loud-storage) :b1 :binding {:id :x :name "whatever"})))
    (is (nil? (uniq/check-fn-name-collision!
                (loud-storage) :b1 :fn {:id :x})))))


;; === check-list-item-position-collisions! ===

(defn- list-item-db
  "Branch b1 (root). Binding :bd1 of fn :f1 holds item :i1 at position 0
   and :i2 at position 1 (live on b1); :i-dead is tombstoned at
   position 5; :i-off holds position 0 but only on unrelated branch b2."
  []
  {:branch {:b1 {:id :b1}
            :b2 {:id :b2}}
   :fn {:f1 {:id :f1 :parent-ids []}}
   :binding {:bd1 {:id :bd1 :fn-id :f1}}
   :binding-list-item {:i1 {:id :i1 :binding-id :bd1}
                       :i2 {:id :i2 :binding-id :bd1}
                       :i-dead {:id :i-dead :binding-id :bd1}
                       :i-off {:id :i-off :binding-id :bd1}}
   :binding-list-item-version
   {:v1 {:id :v1 :item-id :i1 :binding-id :bd1 :branch-id :b1
         :position 0 :created-at 1}
    :v2 {:id :v2 :item-id :i2 :binding-id :bd1 :branch-id :b1
         :position 1 :created-at 1}
    :v-dead-1 {:id :v-dead-1 :item-id :i-dead :binding-id :bd1 :branch-id :b1
               :position 5 :created-at 1}
    :v-dead-2 {:id :v-dead-2 :item-id :i-dead :binding-id :bd1 :branch-id :b1
               :position 5 :created-at 2 :deleted-at 2}
    :v-off {:id :v-off :item-id :i-off :binding-id :bd1 :branch-id :b2
            :position 7 :created-at 1}}})


(deftest position-collision-throws-on-taken-position
  (with-isolated-chain-cache
    (let [storage (mem-storage (list-item-db))
          data (err-data #(uniq/check-list-item-position-collision!
                            storage :b1 :binding-list-item
                            {:id :i-new :binding-id :bd1 :position 0}))]
      (is (= :constraint-violation/position-collision (:type data)))
      (is (= [:i1] (:colliding-item-ids data)))
      (is (zero? (:position data)))
      (is (= :bd1 (:binding-id data)))
      (is (string? (:reason data))))))


(deftest position-collision-resolved-view-exemptions
  (with-isolated-chain-cache
    (let [storage (mem-storage (list-item-db))]
      (testing "a free position passes"
        (is (nil? (uniq/check-list-item-position-collision!
                    storage :b1 :binding-list-item
                    {:id :i-new :binding-id :bd1 :position 9}))))

      (testing "a tombstoned item's position is free again"
        (is (nil? (uniq/check-list-item-position-collision!
                    storage :b1 :binding-list-item
                    {:id :i-new :binding-id :bd1 :position 5}))))

      (testing "a position held only on an unrelated branch is free here"
        (is (nil? (uniq/check-list-item-position-collision!
                    storage :b1 :binding-list-item
                    {:id :i-new :binding-id :bd1 :position 7}))))

      (testing "the item keeping its own position does not collide with itself"
        (is (nil? (uniq/check-list-item-position-collision!
                    storage :b1 :binding-list-item
                    {:id :i1 :binding-id :bd1 :position 0})))))))


(deftest position-collision-batch-swap-checks-post-batch-view
  (with-isolated-chain-cache
    (let [storage (mem-storage (list-item-db))]
      (testing "a same-batch permutation passes: each item's old position is
                vacated by the batch itself"
        (is (nil? (uniq/check-list-item-position-collisions!
                    storage :b1 :binding-list-item
                    [{:id :i1 :binding-id :bd1 :position 1}
                     {:id :i2 :binding-id :bd1 :position 0}]))))

      (testing "a batch landing on a position a NON-moving sibling holds
                still throws"
        (is (= :constraint-violation/position-collision
               (:type (err-data #(uniq/check-list-item-position-collisions!
                                   storage :b1 :binding-list-item
                                   [{:id :i-new :binding-id :bd1 :position 1}])))))))))


(deftest position-collision-merge-surfaced-item-collides
  ;; The documented merge case: an item versioned ONLY on a merge-source
  ;; branch resolves onto the target via the branch-merge row, so its
  ;; position occupies the target's resolved view.
  (with-isolated-chain-cache
    (let [db (-> (list-item-db)
                 (assoc-in [:binding-list-item :i-merged]
                           {:id :i-merged :binding-id :bd1})
                 (assoc-in [:binding-list-item-version :v-merged]
                           {:id :v-merged :item-id :i-merged :binding-id :bd1
                            :branch-id :b2 :position 3 :created-at 5})
                 (assoc :branch-merge
                        {:m1 {:id :m1 :source-branch-id :b2 :target-branch-id :b1
                              :source-timestamp 10 :target-timestamp 20}}))
          storage (mem-storage db)
          data (err-data #(uniq/check-list-item-position-collision!
                            storage :b1 :binding-list-item
                            {:id :i-new :binding-id :bd1 :position 3}))]
      (is (= :constraint-violation/position-collision (:type data)))
      (is (= [:i-merged] (:colliding-item-ids data))))))


(deftest position-collision-short-circuits
  (testing "non-list-item entities and position-less/binding-less writes
            never touch storage"
    (is (nil? (uniq/check-list-item-position-collision!
                (loud-storage) :b1 :fn {:id :x :position 0})))
    (is (nil? (uniq/check-list-item-position-collision!
                (loud-storage) :b1 :binding-list-item {:id :x :binding-id :bd1})))
    (is (nil? (uniq/check-list-item-position-collisions!
                (loud-storage) :b1 :binding-list-item
                [{:id :x :position nil} {:id :y :binding-id nil :position 0}])))))


;; === check-resource-override-path-collision! ===

(defn- override-db
  []
  {:branch {:b1 {:id :b1}}
   :resource-override {:o-live {:id :o-live}
                       :o-dead {:id :o-dead}}
   :resource-override-version
   {:ov-live {:id :ov-live :override-id :o-live :branch-id :b1
              :path "/editor.js" :created-at 1}
    :ov-dead-1 {:id :ov-dead-1 :override-id :o-dead :branch-id :b1
                :path "/gone.css" :created-at 1}
    :ov-dead-2 {:id :ov-dead-2 :override-id :o-dead :branch-id :b1
                :path "/gone.css" :created-at 2 :deleted-at 2}}})


(deftest resource-override-path-collision-test
  (with-isolated-chain-cache
    (let [storage (mem-storage (override-db))]
      (testing "a live override's path collides"
        (let [data (err-data #(uniq/check-resource-override-path-collision!
                                storage :b1 :resource-override
                                {:id :o-new :path "/editor.js"}))]
          (is (= :constraint-violation/resource-override-path-collision
                 (:type data)))
          (is (= [:o-live] (:colliding-ids data)))
          (is (= "/editor.js" (:path data)))))

      (testing "a tombstoned override's path is free again"
        (is (nil? (uniq/check-resource-override-path-collision!
                    storage :b1 :resource-override
                    {:id :o-new :path "/gone.css"}))))

      (testing "self-update of the live override does not collide"
        (is (nil? (uniq/check-resource-override-path-collision!
                    storage :b1 :resource-override
                    {:id :o-live :path "/editor.js"})))))))


(deftest resource-override-path-collision-short-circuits
  (is (nil? (uniq/check-resource-override-path-collision!
              (loud-storage) :b1 :fn {:id :x :path "/y"})))
  (is (nil? (uniq/check-resource-override-path-collision!
              (loud-storage) :b1 :resource-override {:id :x}))))


;; === advisory-lock key builders ===
;;
;; Only the KEY construction is unit-testable — taking the lock itself
;; is a pg_advisory_xact_lock call and stays integration-covered.

(deftest fn-name-lock-key-test
  (testing "a named :fn write yields the (branch, namespace, name) key"
    (is (= "fn-name|b1|ns1|taken"
           (uniq/fn-name-lock-key "b1" :fn {:name "taken" :namespace-id "ns1"}))))

  (testing "root fns (nil namespace) still key deterministically"
    (is (= "fn-name|b1||taken"
           (uniq/fn-name-lock-key "b1" :fn {:name "taken"}))))

  (testing "anonymous fns and non-:fn writes need no lock"
    (is (nil? (uniq/fn-name-lock-key "b1" :fn {})))
    (is (nil? (uniq/fn-name-lock-key "b1" :binding {:name "x"})))))


(deftest resource-override-path-lock-key-test
  (is (= "resource-override-path|b1|/editor.js"
         (uniq/resource-override-path-lock-key
           "b1" :resource-override {:path "/editor.js"})))
  (is (nil? (uniq/resource-override-path-lock-key
              "b1" :resource-override {})))
  (is (nil? (uniq/resource-override-path-lock-key "b1" :fn {:path "/x"}))))
