(ns graphden.schema.placement.schema-test
  "Unit tests for the `:placement` schema extension
   (`graphden.schema.placement.schema`) — the fleet control-plane
   routing map `(org, entry-fn-id) → executor-id` from
   docs/FLEET_RFC.md §6.1.

   Contracts under test: the builder extension composes after the
   graph schema without disturbing core entities; the entity's fields
   land with their declared types/nullability; the entity + field
   UUIDs stay STABLE (they are migration identity — a drift here is a
   destructive schema change); it cannot build without the graph
   schema it refs (`:entry-fn-id → :fn`); double application is
   rejected; and `:placement` stays NON-versioned (absent from the
   versioned-resolution entity-config)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as malli]
    [graphden.schema.placement.schema :as ps]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.versioning.storage.resolution :as resolution]))


(defn- built-schema
  []
  (-> (malli/create-builder)
      (gds/extend-builder)
      (ps/extend-builder)
      (ds/build)))


(deftest extend-builder-returns-builder-test
  (testing "extend-builder returns a chainable builder, not a schema"
    (is (satisfies? ds/DataSchemaBuilder
                    (-> (malli/create-builder)
                        (gds/extend-builder)
                        (ps/extend-builder))))))


(deftest placement-entity-fields-test
  (let [schema (built-schema)
        fields (ds/entity-fields schema :placement)]
    (testing "the :placement entity exists with exactly the declared fields"
      (is (some? fields))
      (is (= #{:org :entry-fn-id :executor-id :epoch} (set (keys fields)))))
    (testing ":org is nullable text (nil ⇒ platform/public cell)"
      (is (= :text (:type (:org fields))))
      (is (true? (:nullable? (:org fields)))))
    (testing ":entry-fn-id is a required logical ref to :fn"
      (is (= :ref (:type (:entry-fn-id fields))))
      (is (= :fn (:ref-entity (:entry-fn-id fields))))
      (is (not (:nullable? (:entry-fn-id fields)))))
    (testing ":executor-id is required text; :epoch is a required int"
      (is (= :text (:type (:executor-id fields))))
      (is (not (:nullable? (:executor-id fields))))
      (is (= :int (:type (:epoch fields))))
      (is (not (:nullable? (:epoch fields)))))))


(deftest placement-uuids-are-stable-test
  (testing "entity + field UUIDs are pinned — they are migration identity,
            so a change here would read as a destructive rename"
    (let [schema (built-schema)
          fields (ds/entity-fields schema :placement)]
      (is (= #uuid "1500e37c-0cd4-4636-b992-99b802c91b6c"
             (ds/entity-uuid schema :placement)))
      (is (= #uuid "e3747a57-88a3-4374-9129-e7ac843dcfe3"
             (:uuid (:org fields))))
      (is (= #uuid "bda23d34-72fb-4b82-a1a2-f3048ed0e88f"
             (:uuid (:entry-fn-id fields))))
      (is (= #uuid "3f2ed574-f220-4bd3-87d5-e0b0d44e3d78"
             (:uuid (:executor-id fields))))
      (is (= #uuid "771a2a91-2ab1-4f00-88bf-6e8dc9bf307e"
             (:uuid (:epoch fields)))))))


(deftest composes-without-disturbing-core-test
  (testing "chaining :placement leaves the core graph entities intact"
    (let [schema (built-schema)]
      (is (some #{:placement} (ds/entities schema)))
      (is (some? (ds/entity-fields schema :fn)))
      (is (some? (ds/entity-fields schema :slot))))))


(deftest requires-graph-schema-test
  (testing "building WITHOUT the graph schema fails — :entry-fn-id refs :fn"
    (is (thrown? clojure.lang.ExceptionInfo
          (-> (malli/create-builder)
              (ps/extend-builder)
              (ds/build))))))


(deftest double-application-rejected-test
  (testing "applying the extension twice is a duplicate-entity error, not a
            silent overwrite"
    (is (thrown? clojure.lang.ExceptionInfo
          (-> (malli/create-builder)
              (gds/extend-builder)
              (ps/extend-builder)
              (ps/extend-builder))))))


(deftest placement-is-not-versioned-test
  (testing ":placement is control-plane state — it must NOT appear in the
            versioned-resolution entity-config (writes pass straight through)"
    (is (not (contains? resolution/entity-config :placement)))))
