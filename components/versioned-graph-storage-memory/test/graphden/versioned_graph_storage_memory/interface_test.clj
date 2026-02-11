(ns graphden.versioned-graph-storage-memory.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.fn-composition.interface :as fn-composition]
    [graphden.fn-registry.interface :as registry]
    [graphden.storage-protocol.interface :as sp]
    [graphden.versioned-graph-storage-memory.interface :as vgsm]
    [graphden.versioned-storage.interface :as vs]))


(deftest create-storage-test
  (testing "creates versioned storage with all graph + versioning entities"
    (let [storage (vgsm/create-storage)]
      (is (contains? (sp/current-entities storage) :fn))
      (is (contains? (sp/current-entities storage) :fn-schema))
      (is (contains? (sp/current-entities storage) :arg-schema))
      (is (contains? (sp/current-entities storage) :fn-version))
      (is (contains? (sp/current-entities storage) :branch))
      (is (contains? (sp/current-entities storage) :branch-merge))
      (sp/close storage)))

  (testing "storage is a VersionedStorage"
    (let [storage (vgsm/create-storage)]
      (is (vs/versioned-storage? storage))
      (sp/close storage)))

  (testing "starts on main branch"
    (let [storage (vgsm/create-storage)
          branch-id (vs/current-branch-id storage)
          branch (vs/get-branch storage branch-id)]
      (is (= "main" (:name branch)))
      (sp/close storage)))

  (testing "CRUD works through versioned layer"
    (let [storage (vgsm/create-storage)
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema
                              {:id schema-id :name "test-schema" :returned-type :int})
          fn-data (sp/create-entity storage :fn
                                    {:name "test-fn" :fn-schema-id schema-id})
          read-back (sp/read-entity storage :fn (:id fn-data))]
      (is (= "test-fn" (:name read-back)))
      (sp/close storage)))

  (testing "branching works end-to-end"
    (let [storage (vgsm/create-storage)
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema
                              {:id schema-id :name "test-schema" :returned-type :int})
          fn-data (sp/create-entity storage :fn
                                    {:name "main-fn" :fn-schema-id schema-id})
          ;; Create feature branch
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          ;; Update on feature branch
          _ (sp/update-entity feature :fn (:id fn-data) {:name "feature-fn"})
          ;; main still sees original
          main-read (sp/read-entity storage :fn (:id fn-data))
          ;; feature sees update
          feature-read (sp/read-entity feature :fn (:id fn-data))]
      (is (= "main-fn" (:name main-read)))
      (is (= "feature-fn" (:name feature-read)))
      (sp/close storage)))

  (testing "custom branch-name option"
    (let [storage (vgsm/create-storage {:branch-name "develop"})
          branch-id (vs/current-branch-id storage)
          branch (vs/get-branch storage branch-id)]
      (is (= "develop" (:name branch)))
      (sp/close storage))))


;; === fn-registry integration tests ===

(deftest fn-registry-sync-test
  (testing "sync-defs-to-storage! works with versioned storage"
    (let [storage (vgsm/create-storage)
          defs {:test-add {:args {:a :int :b :int}
                           :return-type :int
                           :impl (fn [_ _] 42)}}]
      (registry/register-base-fns! defs)
      (let [result (registry/sync-defs-to-storage! storage defs)]
        (is (some? result))
        ;; fn-schema should be queryable through versioned layer
        (let [schemas (sp/query-entities storage :fn-schema {:name "test-add"})]
          (is (= 1 (count schemas)))
          (is (= "test-add" (:name (first schemas)))))
        ;; arg-schemas should be created
        (let [arg-schemas (sp/query-entities storage :arg-schema {})]
          (is (>= (count arg-schemas) 2))))
      (sp/close storage)))

  (testing "sync is idempotent on versioned storage"
    (let [storage (vgsm/create-storage)
          defs {:test-idem {:args {:x :int}
                            :return-type :int
                            :impl (fn [_ _] 1)}}]
      (registry/register-base-fns! defs)
      (registry/sync-defs-to-storage! storage defs)
      ;; Second sync should not create duplicates
      (registry/sync-defs-to-storage! storage defs)
      (let [schemas (sp/query-entities storage :fn-schema {:name "test-idem"})]
        (is (= 1 (count schemas))))
      (sp/close storage)))

  (testing "synced schemas visible on child branch via inheritance"
    (let [storage (vgsm/create-storage)
          defs {:test-inherit {:args {:x :int}
                               :return-type :int
                               :impl (fn [_ _] 1)}}]
      (registry/register-base-fns! defs)
      (registry/sync-defs-to-storage! storage defs)
      ;; Create child branch
      (let [branch (vs/create-branch! storage "feature")
            feature (vs/switch-branch storage (:id branch))
            schemas (sp/query-entities feature :fn-schema {:name "test-inherit"})]
        (is (= 1 (count schemas)))
        (is (= "test-inherit" (:name (first schemas)))))
      (sp/close storage))))


;; === fn-composition integration tests ===

(deftest fn-composition-sync-test
  (testing "sync-fns-to-storage! works with versioned storage"
    (let [storage (vgsm/create-storage)
          base-defs {:const-v {:args {:x :any}
                               :return-type :any
                               :impl (fn [{:keys [x]} _] @x)}
                     :add-v {:args {:a :int :b :int}
                             :return-type :int
                             :impl (fn [{:keys [a b]} _] (+ @a @b))}}]
      (registry/register-base-fns! base-defs)
      (registry/sync-defs-to-storage! storage base-defs)
      ;; Sync fn-defs
      (let [fn-defs [{:name :five-fn
                      :parent :const-v
                      :args {:x 5}}
                     {:name :three-fn
                      :parent :const-v
                      :args {:x 3}}]
            result (fn-composition/sync-fns-to-storage! storage fn-defs)]
        (is (map? result))
        (is (contains? result :five-fn))
        (is (contains? result :three-fn))
        ;; Verify fns exist in storage
        (let [five-fn (sp/read-entity storage :fn (:five-fn result))]
          (is (some? five-fn))
          (is (= "five-fn" (:name five-fn)))))
      (sp/close storage)))

  (testing "fn-composition on feature branch with different values"
    (let [storage (vgsm/create-storage)
          base-defs {:const-branch {:args {:x :any}
                                    :return-type :any
                                    :impl (fn [{:keys [x]} _] @x)}}]
      (registry/register-base-fns! base-defs)
      (registry/sync-defs-to-storage! storage base-defs)
      ;; Create fn on main
      (let [main-result (fn-composition/sync-fns-to-storage! storage
                                                             [{:name :val-fn
                                                               :parent :const-branch
                                                               :args {:x 100}}])
            ;; Create feature branch
            branch (vs/create-branch! storage "feature")
            feature (vs/switch-branch storage (:id branch))
            ;; Update fn on feature branch
            _ (sp/update-entity feature :fn (:val-fn main-result)
                                {:name "val-fn-updated"})
            ;; Main sees original
            main-fn (sp/read-entity storage :fn (:val-fn main-result))
            ;; Feature sees update
            feature-fn (sp/read-entity feature :fn (:val-fn main-result))]
        (is (= "val-fn" (:name main-fn)))
        (is (= "val-fn-updated" (:name feature-fn))))
      (sp/close storage)))

  (testing "merge brings fn-composition changes to main"
    (let [storage (vgsm/create-storage)
          base-defs {:const-merge {:args {:x :any}
                                   :return-type :any
                                   :impl (fn [{:keys [x]} _] @x)}}]
      (registry/register-base-fns! base-defs)
      (registry/sync-defs-to-storage! storage base-defs)
      ;; Create fn on main
      (let [main-result (fn-composition/sync-fns-to-storage! storage
                                                             [{:name :merge-fn
                                                               :parent :const-merge
                                                               :args {:x 10}}])
            ;; Create feature branch and update
            branch (vs/create-branch! storage "merge-test")
            feature (vs/switch-branch storage (:id branch))
            _ (sp/update-entity feature :fn (:merge-fn main-result)
                                {:name "merge-fn-v2"})
            ;; Merge feature into main
            _ (vs/merge-branch! storage (:id branch))
            ;; Main should now see the feature update
            merged-fn (sp/read-entity storage :fn (:merge-fn main-result))]
        (is (= "merge-fn-v2" (:name merged-fn))))
      (sp/close storage))))
