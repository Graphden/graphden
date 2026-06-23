(ns graphden.versioning.branch-local-test
  "Tests for `graphden.versioning.branch-local` — effective-branch-
   local? walker + cache + monotonic OR over `:parent-ids`.

   Storage stack mirrors `versioning.storage.core-test`.

   `pure-set-builder-test` is unit (in-memory fn map); the
   storage-* tests are tagged per-deftest `^:integration` and skip in
   `bb coverage` accordingly."
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
    [graphden.versioning.branch-local :as bl]))


(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))
(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(defn- base-storage
  []
  (let [schema (-> (mds/create-builder)
                   (gds/extend-builder)
                   (vds/extend-builder)
                   (vts/extend-builder)
                   (ds/build))]
    (-> (pg/create-storage (th/get-container-config *container*))
        (sp/initialize-with-cleanup! schema))))


(defn- create-fn!
  [storage {:keys [name parents branch-local?]}]
  (sp/create-entity storage :fn
                    (cond-> {:id (random-uuid)
                             :name name
                             :parent-ids (vec parents)}
                      (some? branch-local?) (assoc :branch-local? branch-local?))))


(deftest pure-set-builder-test
  (testing "build-branch-local-set: seed propagates downward by parent-ids"
    (let [a-id (random-uuid)
          b-id (random-uuid)
          c-id (random-uuid)
          d-id (random-uuid)
          fns {a-id {:id a-id :parent-ids []        :branch-local? true}
               b-id {:id b-id :parent-ids [a-id]    :branch-local? nil}
               c-id {:id c-id :parent-ids [b-id]    :branch-local? nil}
               d-id {:id d-id :parent-ids []        :branch-local? nil}}
          result (bl/build-branch-local-set fns)]
      (is (contains? result a-id) "seed: own true")
      (is (contains? result b-id) "child of seed inherits")
      (is (contains? result c-id) "grandchild propagates through one hop")
      (is (not (contains? result d-id)) "unrelated fn stays false")))

  (testing "MI OR: any local parent ⇒ effective local"
    (let [local (random-uuid)
          plain (random-uuid)
          child (random-uuid)
          fns {local {:id local :parent-ids [] :branch-local? true}
               plain {:id plain :parent-ids [] :branch-local? nil}
               child {:id child :parent-ids [local plain] :branch-local? nil}}]
      (is (contains? (bl/build-branch-local-set fns) child)))))


(deftest nil-fn-id-short-circuit-test
  (testing "effective-branch-local? returns false for nil fn-id (defensive)"
    ;; Doesn't touch storage at all — early `(if (nil? fn-id) false ...)` guard.
    (is (false? (bl/effective-branch-local? ::any-opaque-storage nil)))))


(deftest invalidate-and-invalidate-all-test
  (testing "invalidate! on a never-seen storage is a no-op (no NPE)"
    ;; Per docstring: invalidate! drops a per-storage entry IF one
    ;; exists. Fresh storage has no entry → noop, no throw.
    (is (nil? (bl/invalidate! (Object.)))))

  (testing "invalidate-all! resets the global cache map"
    ;; Set up two fake storage objects, exercise cache-for-storage
    ;; via effective-branch-local?-on-nil (which still goes through
    ;; the nil-guard, NOT through cache-for-storage). Use the
    ;; lookups directly: do a swap on the global atom to seed entries,
    ;; then invalidate-all! and verify the global is empty.
    (let [storage-caches @#'bl/storage-caches
          fake-k 999999999]
      (swap! storage-caches assoc fake-k (atom {:seeded-fn-id true}))
      (is (contains? @storage-caches fake-k) "pre-condition: seeded")
      (bl/invalidate-all!)
      (is (= {} @storage-caches) "global cleared"))))


(deftest ^:integration storage-walker-test
  (let [base (base-storage)]
    (try
      (let [root  (create-fn! base {:name "root-local" :parents []
                                    :branch-local? true})
            mid   (create-fn! base {:name "mid" :parents [(:id root)]})
            leaf  (create-fn! base {:name "leaf" :parents [(:id mid)]})
            other (create-fn! base {:name "other" :parents []})]
        (bl/invalidate! base)
        (testing "own true ⇒ effective true"
          (is (true? (bl/effective-branch-local? base (:id root)))))
        (testing "ancestor true at depth-2 ⇒ effective true"
          (is (true? (bl/effective-branch-local? base (:id mid))))
          (is (true? (bl/effective-branch-local? base (:id leaf)))))
        (testing "unrelated fn ⇒ effective false"
          (is (false? (bl/effective-branch-local? base (:id other)))))
        (testing "nil fn-id ⇒ false (defensive)"
          (is (false? (bl/effective-branch-local? base nil)))))
      (finally (sp/close base) (bl/invalidate-all!)))))


(deftest ^:integration cache-invalidation-test
  (let [base (base-storage)]
    (try
      (let [foo (create-fn! base {:name "foo" :parents []})]
        (bl/invalidate! base)
        (testing "initial: foo has no flag"
          (is (false? (bl/effective-branch-local? base (:id foo)))))
        (sp/update-entity base :fn (:id foo) {:branch-local? true})
        (testing "stale cache returns old result before invalidate"
          ;; Without invalidate, the per-storage cache still has false.
          (is (false? (bl/effective-branch-local? base (:id foo)))))
        (bl/invalidate! base)
        (testing "after invalidate: re-walk picks up the new flag"
          (is (true? (bl/effective-branch-local? base (:id foo))))))
      (finally (sp/close base) (bl/invalidate-all!)))))
