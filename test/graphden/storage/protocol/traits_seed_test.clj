(ns graphden.storage.protocol.traits-seed-test
  "Unit test of the seed-traits! contract — the row shape written for
   the built-in trait, and the idempotence guard (a present row means
   NO create call). A protocol stub stands in for storage — every
   method the seed must not touch fails loud; the real-DB write path
   stays integration-covered."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.traits.schema :as traits]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.traits-seed :as traits-seed]))


(defn- unexpected!
  [method]
  (throw (AssertionError. (str method " must not run during seed-traits!"))))


(deftest seeds-merge-protected-trait-when-absent
  (testing "an absent trait row is created under the well-known UUID
            with the canonical name/description"
    (let [created (atom nil)
          storage (reify sp/StorageCRUD
                    (read-entity
                      [_ entity-name id]
                      (is (= :trait entity-name))
                      (is (= traits/merge-protected-trait-uuid id))
                      nil)

                    (create-entity
                      [_ entity-name data]
                      (reset! created [entity-name data])
                      data)

                    (update-entity [_ _ _ _] (unexpected! "update-entity"))

                    (delete-entity [_ _ _] (unexpected! "delete-entity"))

                    (query-entities [_ _ _] (unexpected! "query-entities"))

                    (query-entities [_ _ _ _] (unexpected! "query-entities"))

                    (query-latest-per-group
                      [_ _ _ _]
                      (unexpected! "query-latest-per-group")))]
      (traits-seed/seed-traits! storage)
      (let [[entity-name row] @created]
        (is (= :trait entity-name))
        (is (= traits/merge-protected-trait-uuid (:id row)))
        (is (= "merge-protected" (:name row)))
        (is (string? (:description row)))))))


(deftest seed-is-idempotent-when-trait-exists
  (testing "a present row short-circuits — create-entity is never
            called on a re-seed"
    (let [storage (reify sp/StorageCRUD
                    (read-entity [_ _ id] {:id id})

                    (create-entity [_ _ _] (unexpected! "create-entity"))

                    (update-entity [_ _ _ _] (unexpected! "update-entity"))

                    (delete-entity [_ _ _] (unexpected! "delete-entity"))

                    (query-entities [_ _ _] (unexpected! "query-entities"))

                    (query-entities [_ _ _ _] (unexpected! "query-entities"))

                    (query-latest-per-group
                      [_ _ _ _]
                      (unexpected! "query-latest-per-group")))]
      (is (nil? (traits-seed/seed-traits! storage))))))
