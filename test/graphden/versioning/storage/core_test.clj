(ns ^:integration graphden.versioning.storage.core-test
  "Tests for versioned storage with 2-entity schema.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as res]))


;; Container for PostgreSQL tests
(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))
(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(defn- create-test-storage
  "Creates a versioned storage initialized with versioned schema on main branch.
   Cleans the database before creating storage to ensure test isolation."
  []
  (th/clean-database-fast! *container*)
  (let [schema (vds/build-schema (mds/create-builder))
        base (-> (pg/create-storage (th/get-container-config *container*))
                 (sp/initialize-with-cleanup! schema))]
    (vs/wrap-with-versioning base)))


;; === Initialization Tests ===

(deftest wrap-with-versioning-test
  (testing "creates main branch automatically"
    (let [storage (create-test-storage)]
      (is (vs/versioned-storage? storage))
      (is (some? (vs/current-branch-id storage)))
      (let [branches (vs/list-branches storage)]
        (is (= 1 (count branches)))
        (is (= "main" (:name (first branches))))))))


(deftest wrap-idempotent-test
  (testing "wrapping twice reuses existing main branch"
    (th/clean-database-fast! *container*)
    (let [schema (vds/build-schema (mds/create-builder))
          base (-> (pg/create-storage (th/get-container-config *container*))
                   (sp/initialize-with-cleanup! schema))
          s1 (vs/wrap-with-versioning base)
          s2 (vs/wrap-with-versioning base)]
      (is (= (vs/current-branch-id s1) (vs/current-branch-id s2)))
      (is (= 1 (count (vs/list-branches s1)))))))


;; === Basic CRUD on Versioned Entities ===

(deftest fn-crud-test
  (testing "create and read fn entity"
    (let [storage (create-test-storage)
          fn-rec (sp/create-entity storage :fn
                                   {:name "test-fn" :parent-id nil :return-type :int})]
      (is (some? (:id fn-rec)))
      (is (= "test-fn" (:name fn-rec)))
      (is (nil? (:parent-id fn-rec)))
      (is (= :int (:return-type fn-rec)))

      (testing "read returns same record"
        (let [read-fn (sp/read-entity storage :fn (:id fn-rec))]
          (is (= "test-fn" (:name read-fn)))
          (is (nil? (:parent-id read-fn))))))))


(deftest fn-with-parent-crud-test
  (testing "create composed fn with parent-id"
    (let [storage (create-test-storage)
          base-fn (sp/create-entity storage :fn
                                    {:name "base-add" :parent-id nil :return-type :int})
          composed-fn (sp/create-entity storage :fn
                                        {:name "add-1-2" :parent-id (:id base-fn) :return-type :int})]
      (is (some? (:id composed-fn)))
      (is (= (:id base-fn) (:parent-id composed-fn)))

      (testing "read preserves parent-id"
        (let [read-fn (sp/read-entity storage :fn (:id composed-fn))]
          (is (= (:id base-fn) (:parent-id read-fn))))))))


(deftest arg-crud-test
  (testing "create and read arg entity"
    (let [storage (create-test-storage)
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-fn" :parent-id nil :return-type :int})
          arg (sp/create-entity storage :arg
                                {:fn-id (:id fn-rec) :name "x" :type :int :required true :is-fn false :value 42})]
      (is (some? (:id arg)))
      (is (= (:id fn-rec) (:fn-id arg)))
      (is (= "x" (:name arg)))
      (is (= :int (:type arg)))
      (is (true? (:required arg)))
      (is (= 42 (:value arg)))

      (testing "query arg by fn-id"
        (let [found (sp/query-entities storage :arg {:fn-id (:id fn-rec)})]
          (is (= 1 (count found)))
          (is (= (:id arg) (:id (first found)))))))))


(deftest arg-with-ref-id-test
  (testing "create arg with ref-id reference"
    (let [storage (create-test-storage)
          base-fn (sp/create-entity storage :fn
                                    {:name "base" :parent-id nil :return-type :int})
          ref-fn (sp/create-entity storage :fn
                                   {:name "ref-target" :parent-id nil :return-type :int})
          composed-fn (sp/create-entity storage :fn
                                        {:name "composed" :parent-id (:id base-fn) :return-type :int})
          arg (sp/create-entity storage :arg
                                {:fn-id (:id composed-fn) :name "x" :type :int
                                 :required true :is-fn false :ref-id (:id ref-fn)})]
      (is (= (:id ref-fn) (:ref-id arg))))))


;; === Update Creates New Version ===

(deftest update-creates-new-version-test
  (testing "update appends version, read returns latest"
    (let [storage (create-test-storage)
          fn-rec (sp/create-entity storage :fn
                                   {:name "original" :parent-id nil :return-type :int})
          updated (sp/update-entity storage :fn (:id fn-rec)
                                    {:name "updated"})]
      (is (= "updated" (:name updated)))
      (is (nil? (:parent-id updated)))
      (is (= "updated" (:name (sp/read-entity storage :fn (:id fn-rec))))))))


(deftest update-arg-test
  (testing "update arg value"
    (let [storage (create-test-storage)
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-fn" :parent-id nil :return-type :int})
          arg (sp/create-entity storage :arg
                                {:fn-id (:id fn-rec) :name "x" :type :int
                                 :required true :is-fn false :value 1})
          updated (sp/update-entity storage :arg (:id arg)
                                    {:value 99})]
      (is (= 99 (:value updated)))
      (is (= 99 (:value (sp/read-entity storage :arg (:id arg))))))))


;; === Delete on Branch ===

(deftest delete-versioned-entity-test
  (testing "delete removes version records on current branch"
    (let [storage (create-test-storage)
          fn-rec (sp/create-entity storage :fn
                                   {:name "to-delete" :parent-id nil :return-type :int})]
      (is (some? (sp/read-entity storage :fn (:id fn-rec))))
      (is (true? (sp/delete-entity storage :fn (:id fn-rec))))
      (is (nil? (sp/read-entity storage :fn (:id fn-rec)))))))


(deftest delete-nonexistent-returns-false-test
  (testing "delete of entity with no versions returns false"
    (let [storage (create-test-storage)]
      (is (false? (sp/delete-entity storage :fn (random-uuid)))))))


;; === Query Tests ===

(deftest query-versioned-entities-test
  (testing "query returns resolved entities"
    (let [storage (create-test-storage)
          _ (sp/create-entity storage :fn {:name "fn1" :parent-id nil :return-type :int})
          _ (sp/create-entity storage :fn {:name "fn2" :parent-id nil :return-type :text})
          all-fns (sp/query-entities storage :fn {})]
      (is (= 2 (count all-fns)))

      (testing "filter by version field"
        (is (= 1 (count (sp/query-entities storage :fn {:name "fn1"})))))

      (testing "filter by return-type"
        (is (= 1 (count (sp/query-entities storage :fn {:return-type :int}))))))))


(deftest query-args-test
  (testing "query args by fn-id"
    (let [storage (create-test-storage)
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-fn" :parent-id nil :return-type :int})
          _ (sp/create-entity storage :arg
                              {:fn-id (:id fn-rec) :name "x" :type :int :required true :is-fn false})
          _ (sp/create-entity storage :arg
                              {:fn-id (:id fn-rec) :name "y" :type :text :required false :is-fn false})
          found (sp/query-entities storage :arg {:fn-id (:id fn-rec)})]
      (is (= 2 (count found)))
      (is (every? #(= (:id fn-rec) (:fn-id %)) found)))))


;; === Batch Operations ===

(deftest batch-read-versioned-test
  (testing "read-entities returns resolved records for versioned entities"
    (let [storage (create-test-storage)
          f1 (sp/create-entity storage :fn {:name "fn1" :parent-id nil :return-type :int})
          f2 (sp/create-entity storage :fn {:name "fn2" :parent-id nil :return-type :int})
          results (sp/read-entities storage :fn [(:id f1) (:id f2)])]
      (is (= 2 (count results)))
      (is (= "fn1" (:name (get results (:id f1)))))
      (is (= "fn2" (:name (get results (:id f2))))))))


(deftest batch-create-versioned-test
  (testing "create-entities works for versioned entities"
    (let [storage (create-test-storage)
          results (sp/create-entities storage :fn
                                      [{:name "fn1" :parent-id nil :return-type :int}
                                       {:name "fn2" :parent-id nil :return-type :int}])]
      (is (= 2 (count results)))
      (is (= #{"fn1" "fn2"} (set (map :name results)))))))


;; === Branch Operations ===

(deftest create-branch-test
  (testing "create and switch to new branch"
    (let [storage (create-test-storage)
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))]
      (is (= (:id branch) (vs/current-branch-id feature)))
      (is (= "feature" (:name branch)))
      (is (= 2 (count (vs/list-branches storage)))))))


(deftest get-branch-test
  (testing "get-branch returns branch record"
    (let [storage (create-test-storage)
          branch-id (vs/current-branch-id storage)
          branch (vs/get-branch storage branch-id)]
      (is (= "main" (:name branch)))
      (is (nil? (:base-branch-id branch))))))


(deftest switch-nonexistent-branch-throws-test
  (testing "switch to nonexistent branch throws"
    (let [storage (create-test-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Branch not found"
            (vs/switch-branch storage (random-uuid)))))))


;; === Branch Isolation ===

(deftest branch-isolation-read-test
  (testing "child branch inherits parent entities"
    (let [storage (create-test-storage)
          fn-rec (sp/create-entity storage :fn
                                   {:name "main-fn" :parent-id nil :return-type :int})
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))]

      (testing "child sees parent's entity"
        (let [read-on-child (sp/read-entity feature :fn (:id fn-rec))]
          (is (some? read-on-child))
          (is (= "main-fn" (:name read-on-child))))))))


(deftest branch-isolation-update-test
  (testing "update on child doesn't affect parent"
    (let [storage (create-test-storage)
          fn-rec (sp/create-entity storage :fn
                                   {:name "main-fn" :parent-id nil :return-type :int})
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))]

      ;; Update on child
      (sp/update-entity feature :fn (:id fn-rec) {:name "feature-fn"})

      (testing "child sees updated version"
        (is (= "feature-fn" (:name (sp/read-entity feature :fn (:id fn-rec))))))

      (testing "parent still sees original"
        (is (= "main-fn" (:name (sp/read-entity storage :fn (:id fn-rec)))))))))


(deftest branch-isolation-create-test
  (testing "entity created on child not visible on parent"
    (let [storage (create-test-storage)
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          child-fn (sp/create-entity feature :fn
                                     {:name "child-only" :parent-id nil :return-type :int})]

      (testing "visible on child"
        (is (some? (sp/read-entity feature :fn (:id child-fn)))))

      (testing "not visible on parent"
        (is (nil? (sp/read-entity storage :fn (:id child-fn))))))))


(deftest branch-isolation-delete-test
  (testing "delete on child doesn't affect parent"
    (let [storage (create-test-storage)
          ;; Entity created on main
          fn-main (sp/create-entity storage :fn
                                    {:name "shared" :parent-id nil :return-type :int})
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          ;; Entity created only on child
          fn-child (sp/create-entity feature :fn
                                     {:name "child-only" :parent-id nil :return-type :int})]

      ;; Delete child-only entity on child branch
      (is (true? (sp/delete-entity feature :fn (:id fn-child))))

      (testing "child-only entity gone from child"
        (is (nil? (sp/read-entity feature :fn (:id fn-child)))))

      (testing "parent entity still visible on parent"
        (is (some? (sp/read-entity storage :fn (:id fn-main))))
        (is (= "shared" (:name (sp/read-entity storage :fn (:id fn-main))))))

      (testing "parent entity still visible on child via inheritance"
        (is (some? (sp/read-entity feature :fn (:id fn-main))))
        (is (= "shared" (:name (sp/read-entity feature :fn (:id fn-main)))))))))


(deftest branch-isolation-query-test
  (testing "query on branch shows only branch-visible entities"
    (let [storage (create-test-storage)
          _ (sp/create-entity storage :fn
                              {:name "main-fn" :parent-id nil :return-type :int})
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          _ (sp/create-entity feature :fn
                              {:name "feature-fn" :parent-id nil :return-type :int})]

      (testing "parent sees only main entity"
        (is (= 1 (count (sp/query-entities storage :fn {})))))

      (testing "child sees both entities"
        (is (= 2 (count (sp/query-entities feature :fn {}))))))))


;; === Grandchild Branch Inheritance ===

(deftest grandchild-branch-test
  (testing "grandchild inherits from parent chain"
    (let [storage (create-test-storage)
          fn-rec (sp/create-entity storage :fn
                                   {:name "main-fn" :parent-id nil :return-type :int})
          child-branch (vs/create-branch! storage "child")
          child (vs/switch-branch storage (:id child-branch))
          grandchild-branch (vs/create-branch! child "grandchild")
          grandchild (vs/switch-branch child (:id grandchild-branch))]

      (testing "grandchild sees main's entity"
        (is (= "main-fn" (:name (sp/read-entity grandchild :fn (:id fn-rec)))))))))


;; === Deterministic UUID Support ===

(deftest deterministic-uuid-test
  (testing "create with explicit id is idempotent for identity"
    (let [storage (create-test-storage)
          explicit-id (random-uuid)
          f1 (sp/create-entity storage :fn
                               {:id explicit-id :name "fn-v1" :parent-id nil :return-type :int})
          ;; Simulate sync: create again with same id but different data
          f2 (sp/create-entity storage :fn
                               {:id explicit-id :name "fn-v2" :parent-id nil :return-type :int})]
      (is (= explicit-id (:id f1)))
      (is (= explicit-id (:id f2)))
      ;; Read returns the latest version
      (is (= "fn-v2" (:name (sp/read-entity storage :fn explicit-id)))))))


;; === ExecutionGraph Resolution ===

(deftest execution-graph-test
  (testing "resolve-execution-graph works through versioned CRUD"
    (let [storage (create-test-storage)
          ;; Create base fn
          base-fn (sp/create-entity storage :fn
                                    {:name "add" :parent-id nil :return-type :int :impl-hash "abc123"})
          _ (sp/create-entity storage :arg
                              {:fn-id (:id base-fn) :name "a" :type :int :required true :is-fn false})
          _ (sp/create-entity storage :arg
                              {:fn-id (:id base-fn) :name "b" :type :int :required true :is-fn false})
          ;; Create composed fn
          composed-fn (sp/create-entity storage :fn
                                        {:name "add-1-2" :parent-id (:id base-fn) :return-type :int})
          _ (sp/create-entity storage :arg
                              {:fn-id (:id composed-fn) :name "a" :type :int
                               :required true :is-fn false :value 1})
          _ (sp/create-entity storage :arg
                              {:fn-id (:id composed-fn) :name "b" :type :int
                               :required true :is-fn false :value 2})
          graph (sp/resolve-execution-graph storage (:id composed-fn))]
      (is (sp/execution-graph? graph))
      (is (contains? (sp/get-graph-fns graph) (:id composed-fn)))
      (is (contains? (sp/get-graph-fns graph) (:id base-fn))))))


(deftest execution-graph-on-branch-test
  (testing "execution graph resolves on child branch"
    (let [storage (create-test-storage)
          ;; Create base fn on main
          base-fn (sp/create-entity storage :fn
                                    {:name "const" :parent-id nil :return-type :int :impl-hash "xyz"})
          _ (sp/create-entity storage :arg
                              {:fn-id (:id base-fn) :name "x" :type :int :required true :is-fn false})
          ;; Create composed fn
          composed-fn (sp/create-entity storage :fn
                                        {:name "const-42" :parent-id (:id base-fn) :return-type :int})
          _ (sp/create-entity storage :arg
                              {:fn-id (:id composed-fn) :name "x" :type :int
                               :required true :is-fn false :value 42})
          ;; Create branch
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))]

      (testing "main branch graph works"
        (let [graph (sp/resolve-execution-graph storage (:id composed-fn))
              args (sp/get-graph-args graph)]
          (is (some? args))))

      (testing "feature branch can also resolve graph"
        (let [graph (sp/resolve-execution-graph feature (:id composed-fn))]
          (is (sp/execution-graph? graph)))))))


;; === Resolution Algorithm Tests ===

(deftest resolution-versioned-entity-predicate-test
  (testing "versioned-entity? returns correct results"
    (is (true? (res/versioned-entity? :fn)))
    (is (true? (res/versioned-entity? :arg)))
    (is (false? (res/versioned-entity? :branch)))
    (is (false? (res/versioned-entity? :fn-version)))
    (is (false? (res/versioned-entity? :arg-version)))))


;; === Unwrap ===

(deftest unwrap-test
  (testing "unwrap returns base storage"
    (let [storage (create-test-storage)
          base (vs/unwrap storage)]
      (is (not (vs/versioned-storage? base))))))


;; === Merge Tests ===

(deftest merge-no-conflicts-test
  (testing "merge with no conflicts makes source changes visible on target"
    (let [storage (create-test-storage)
          _fn-main (sp/create-entity storage :fn
                                     {:name "main-fn" :parent-id nil :return-type :int})
          ;; Create feature branch and add entity
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          fn-feat (sp/create-entity feature :fn
                                    {:name "feat-fn" :parent-id nil :return-type :int})]

      ;; Before merge: main doesn't see feature entity
      (is (nil? (sp/read-entity storage :fn (:id fn-feat))))

      ;; Merge feature -> main
      (let [merge-rec (vs/merge-branch! storage (:id branch))]
        (is (some? merge-rec))
        (is (uuid? (:id merge-rec))))

      ;; After merge: main sees feature entity
      (is (some? (sp/read-entity storage :fn (:id fn-feat))))
      (is (= "feat-fn" (:name (sp/read-entity storage :fn (:id fn-feat))))))))


(deftest merge-update-visible-test
  (testing "merge makes updates from source visible on target"
    (let [storage (create-test-storage)
          fn-rec (sp/create-entity storage :fn
                                   {:name "original" :parent-id nil :return-type :int})
          ;; Feature branch updates the entity
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))]
      (sp/update-entity feature :fn (:id fn-rec) {:name "updated"})

      ;; Before merge: main sees original
      (is (= "original" (:name (sp/read-entity storage :fn (:id fn-rec)))))

      ;; Merge
      (vs/merge-branch! storage (:id branch))

      ;; After merge: main sees updated
      (is (= "updated" (:name (sp/read-entity storage :fn (:id fn-rec))))))))


(deftest merge-conflict-throws-test
  (testing "merge with conflicts throws when no resolutions provided"
    (let [storage (create-test-storage)
          fn-rec (sp/create-entity storage :fn
                                   {:name "original" :parent-id nil :return-type :int})
          ;; Create feature branch
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))]

      ;; Both branches modify the same entity
      (Thread/sleep 1)  ; Ensure timestamps differ from fork point
      (sp/update-entity storage :fn (:id fn-rec) {:name "main-update"})
      (sp/update-entity feature :fn (:id fn-rec) {:name "feat-update"})

      ;; Merge should throw
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unresolved merge conflicts"
            (vs/merge-branch! storage (:id branch)))))))


(deftest merge-conflict-detection-test
  (testing "detect-conflicts returns conflict details"
    (let [storage (create-test-storage)
          fn-rec (sp/create-entity storage :fn
                                   {:name "original" :parent-id nil :return-type :int})
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))]

      (Thread/sleep 1)
      (sp/update-entity storage :fn (:id fn-rec) {:name "main-v"})
      (sp/update-entity feature :fn (:id fn-rec) {:name "feat-v"})

      (let [{:keys [conflicts fork-point]} (vs/detect-conflicts storage (:id branch))]
        (is (= 1 (count conflicts)))
        (is (some? fork-point))
        (let [c (first conflicts)]
          (is (= :fn (:entity-name c)))
          (is (= (:id fn-rec) (:entity-id c)))
          (is (= "main-v" (:name (:target-version c))))
          (is (= "feat-v" (:name (:source-version c)))))))))


(deftest merge-conflict-resolve-source-test
  (testing "merge with :source resolution keeps source version"
    (let [storage (create-test-storage)
          fn-rec (sp/create-entity storage :fn
                                   {:name "original" :parent-id nil :return-type :int})
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))]

      (Thread/sleep 1)
      (sp/update-entity storage :fn (:id fn-rec) {:name "main-v"})
      (sp/update-entity feature :fn (:id fn-rec) {:name "feat-v"})

      ;; Resolve conflict: take source (feature) version
      (vs/merge-branch! storage (:id branch)
                        {:conflict-resolutions {[:fn (:id fn-rec)] :source}})

      ;; Main should now see the feature version
      (is (= "feat-v" (:name (sp/read-entity storage :fn (:id fn-rec))))))))


(deftest merge-conflict-resolve-target-test
  (testing "merge with :target resolution keeps target version"
    (let [storage (create-test-storage)
          fn-rec (sp/create-entity storage :fn
                                   {:name "original" :parent-id nil :return-type :int})
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))]

      (Thread/sleep 1)
      (sp/update-entity storage :fn (:id fn-rec) {:name "main-v"})
      (sp/update-entity feature :fn (:id fn-rec) {:name "feat-v"})

      ;; Resolve conflict: take target (main) version
      (vs/merge-branch! storage (:id branch)
                        {:conflict-resolutions {[:fn (:id fn-rec)] :target}})

      ;; Main should keep its own version
      (is (= "main-v" (:name (sp/read-entity storage :fn (:id fn-rec))))))))


(deftest merge-multiple-merges-test
  (testing "multiple merges are all visible through resolution"
    (let [storage (create-test-storage)
          ;; Create two feature branches
          b1 (vs/create-branch! storage "feat-1")
          feat1 (vs/switch-branch storage (:id b1))
          b2 (vs/create-branch! storage "feat-2")
          feat2 (vs/switch-branch storage (:id b2))
          ;; Each creates a different entity
          fn1 (sp/create-entity feat1 :fn
                                {:name "fn-from-feat1" :parent-id nil :return-type :int})
          fn2 (sp/create-entity feat2 :fn
                                {:name "fn-from-feat2" :parent-id nil :return-type :int})]

      ;; Merge both into main
      (vs/merge-branch! storage (:id b1))
      (vs/merge-branch! storage (:id b2))

      ;; Main sees both
      (is (= "fn-from-feat1" (:name (sp/read-entity storage :fn (:id fn1)))))
      (is (= "fn-from-feat2" (:name (sp/read-entity storage :fn (:id fn2))))))))


;; === Delete Branch Tests ===

(deftest delete-branch-success-test
  (testing "delete branch removes branch and version records"
    (let [storage (create-test-storage)
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          _fn-feat (sp/create-entity feature :fn
                                     {:name "feat-fn" :parent-id nil :return-type :int})]

      (is (true? (vs/delete-branch! storage (:id branch))))

      ;; Branch is gone
      (is (nil? (vs/get-branch storage (:id branch))))

      ;; Only main branch remains
      (is (= 1 (count (vs/list-branches storage)))))))


(deftest delete-branch-with-children-throws-test
  (testing "delete branch with children throws"
    (let [storage (create-test-storage)
          parent (vs/create-branch! storage "parent")
          parent-vs (vs/switch-branch storage (:id parent))
          _child (vs/create-branch! parent-vs "child")]

      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Branch has child branches"
            (vs/delete-branch! storage (:id parent)))))))


(deftest delete-main-branch-throws-test
  (testing "cannot delete main branch"
    (let [storage (create-test-storage)
          main-id (vs/current-branch-id storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot delete main branch"
            (vs/delete-branch! storage main-id))))))


(deftest delete-branch-parent-unaffected-test
  (testing "after deleting branch, parent data is unaffected"
    (let [storage (create-test-storage)
          fn-main (sp/create-entity storage :fn
                                    {:name "main-fn" :parent-id nil :return-type :int})
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))]
      ;; Modify entity on feature branch
      (sp/update-entity feature :fn (:id fn-main) {:name "feat-version"})

      ;; Delete feature branch
      (vs/delete-branch! storage (:id branch))

      ;; Parent still has original
      (is (= "main-fn" (:name (sp/read-entity storage :fn (:id fn-main))))))))


(deftest delete-nonexistent-branch-throws-test
  (testing "delete nonexistent branch throws :not-found"
    (let [storage (create-test-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Branch not found"
            (vs/delete-branch! storage (random-uuid)))))))


;; === wrap-with-versioning Edge Cases ===

(deftest wrap-with-nonexistent-branch-throws-test
  (testing "wrap-with-versioning with non-existent branch throws"
    (th/clean-database-fast! *container*)
    (let [schema (vds/build-schema (mds/create-builder))
          base (-> (pg/create-storage (th/get-container-config *container*))
                   (sp/initialize-with-cleanup! schema))]
      ;; Create main branch first
      (vs/wrap-with-versioning base)
      ;; Then try to wrap with non-existent branch
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Branch not found"
            (vs/wrap-with-versioning base "nonexistent-branch"))))))


(deftest wrap-with-existing-branch-test
  (testing "wrap-with-versioning with existing branch name works"
    (th/clean-database-fast! *container*)
    (let [schema (vds/build-schema (mds/create-builder))
          base (-> (pg/create-storage (th/get-container-config *container*))
                   (sp/initialize-with-cleanup! schema))
          main-storage (vs/wrap-with-versioning base)
          feature-branch (vs/create-branch! main-storage "feature")]
      ;; Wrap with existing feature branch
      (is (vs/versioned-storage? (vs/wrap-with-versioning base "feature")))
      (is (= (:id feature-branch)
             (vs/current-branch-id (vs/wrap-with-versioning base "feature")))))))


;; === Update Entity Not Found ===

(deftest update-nonexistent-entity-throws-test
  (testing "update versioned entity that doesn't exist throws :not-found"
    (let [storage (create-test-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Entity not found"
            (sp/update-entity storage :fn (random-uuid) {:name "new-name"}))))))


;; === Batch Delete Operations ===

(deftest batch-delete-versioned-test
  (testing "delete-entities works for versioned entities"
    (let [storage (create-test-storage)
          f1 (sp/create-entity storage :fn {:name "fn1" :parent-id nil :return-type :int})
          f2 (sp/create-entity storage :fn {:name "fn2" :parent-id nil :return-type :int})
          f3 (sp/create-entity storage :fn {:name "fn3" :parent-id nil :return-type :int})]
      ;; Delete two of them
      (let [deleted-count (sp/delete-entities storage :fn [(:id f1) (:id f2)])]
        (is (= 2 deleted-count)))
      ;; Verify they're gone
      (is (nil? (sp/read-entity storage :fn (:id f1))))
      (is (nil? (sp/read-entity storage :fn (:id f2))))
      ;; Third still exists
      (is (some? (sp/read-entity storage :fn (:id f3)))))))


;; === Create Branch with Base Branch ID ===

(deftest create-branch-with-base-branch-id-test
  (testing "create-branch! with explicit base-branch-id forks from specified branch"
    (let [storage (create-test-storage)
          ;; Create entity on main
          fn-main (sp/create-entity storage :fn
                                    {:name "main-fn" :parent-id nil :return-type :int})
          ;; Create feature branch from main
          feature-branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id feature-branch))
          ;; Create entity only on feature
          fn-feature (sp/create-entity feature :fn
                                       {:name "feature-fn" :parent-id nil :return-type :int})
          ;; Create hotfix branch from main (not from feature)
          hotfix-branch (vs/create-branch! feature "hotfix"
                                           {:base-branch-id (vs/current-branch-id storage)})
          hotfix (vs/switch-branch storage (:id hotfix-branch))]
      ;; Hotfix should see main's entity
      (is (some? (sp/read-entity hotfix :fn (:id fn-main))))
      ;; Hotfix should NOT see feature's entity (forked from main)
      (is (nil? (sp/read-entity hotfix :fn (:id fn-feature)))))))


;; === Storage Introspection Delegation ===

(deftest storage-introspection-delegation-test
  (testing "VersionedStorage delegates introspection methods to base storage"
    (let [storage (create-test-storage)]
      (testing "current-entities returns expected entities"
        (let [entities (sp/current-entities storage)]
          (is (set? entities))
          (is (contains? entities :fn))
          (is (contains? entities :arg))))

      (testing "current-fields returns field metadata"
        (let [fields (sp/current-fields storage :fn)]
          (is (map? fields))
          (is (some? fields))))

      (testing "current-enums returns enums"
        (let [enums (sp/current-enums storage)]
          (is (or (nil? enums) (set? enums) (sequential? enums)))))

      (testing "schema-metadata returns metadata map"
        (let [metadata (sp/schema-metadata storage)]
          (is (map? metadata)))))))


;; === Storage Lifecycle Delegation ===

(deftest storage-lifecycle-delegation-test
  (testing "VersionedStorage delegates close to base storage"
    (th/clean-database-fast! *container*)
    (let [schema (vds/build-schema (mds/create-builder))
          base (-> (pg/create-storage (th/get-container-config *container*))
                   (sp/initialize-with-cleanup! schema))
          storage (vs/wrap-with-versioning base)]
      ;; Close should not throw
      (is (nil? (sp/close storage))))))


;; === GraphConstraints Delegation ===

(deftest no-dependency-cycle-validation-test
  (testing "validate-no-dependency-cycle! allows non-cyclic dependencies"
    (let [storage (create-test-storage)
          fn-a (sp/create-entity storage :fn
                                 {:name "fn-a" :parent-id nil :return-type :int})
          fn-b (sp/create-entity storage :fn
                                 {:name "fn-b" :parent-id nil :return-type :int})]
      ;; fn-a depends on fn-b should be allowed
      (is (nil? (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-b)))))))


;; === Read Entities Edge Cases ===

(deftest read-entities-missing-ids-test
  (testing "read-entities returns only found records for versioned entities"
    (let [storage (create-test-storage)
          f1 (sp/create-entity storage :fn {:name "fn1" :parent-id nil :return-type :int})
          missing-id (random-uuid)
          results (sp/read-entities storage :fn [(:id f1) missing-id])]
      ;; Should only return the existing record
      (is (= 1 (count results)))
      (is (some? (get results (:id f1))))
      (is (nil? (get results missing-id))))))


;; === Delete Entities Returns Correct Count ===

(deftest delete-entities-partial-count-test
  (testing "delete-entities returns count of actually deleted entities"
    (let [storage (create-test-storage)
          f1 (sp/create-entity storage :fn {:name "fn1" :parent-id nil :return-type :int})
          missing-id (random-uuid)]
      ;; Try to delete one existing and one non-existing
      ;; Only one should be counted as deleted
      (is (= 1 (sp/delete-entities storage :fn [(:id f1) missing-id]))))))
