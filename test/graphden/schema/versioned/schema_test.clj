(ns graphden.schema.versioned.schema-test
  "Tests for versioned data schema with 2-entity model.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)

   Versioning entities:
   - branch, branch-merge: branch management
   - fn-version: version history for fn
   - arg-version: version history for arg"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.malli.core :as malli]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.versioned.schema :as vds]))


(deftest build-schema-test
  (testing "builds schema with graph and versioning entities"
    (let [builder (malli/create-builder)
          schema (vds/build-schema builder)]

      (testing "includes graph entities (2-entity model)"
        (is (some #{:fn} (ds/entities schema)))
        (is (some #{:arg} (ds/entities schema))))

      (testing "includes versioning entities"
        (is (some #{:branch} (ds/entities schema)))
        (is (some #{:branch-merge} (ds/entities schema)))
        (is (some #{:fn-version} (ds/entities schema)))
        (is (some #{:arg-version} (ds/entities schema))))

      (testing "includes value-kind enum"
        (is (contains? (ds/enums schema) :value-kind))))))


(deftest branch-entity-test
  (testing "branch entity has correct fields"
    (let [schema (vds/build-schema (malli/create-builder))
          fields (ds/entity-fields schema :branch)]
      (is (contains? fields :name))
      (is (contains? fields :base-branch-id))
      (is (contains? fields :created-at))

      (testing "base-branch-id is nullable ref to self"
        (is (= :ref (:type (:base-branch-id fields))))
        (is (= :branch (:ref-entity (:base-branch-id fields))))
        (is (true? (:nullable? (:base-branch-id fields))))))))


(deftest branch-merge-entity-test
  (testing "branch-merge entity has correct fields"
    (let [schema (vds/build-schema (malli/create-builder))
          fields (ds/entity-fields schema :branch-merge)]
      (is (= :ref (:type (:source-branch-id fields))))
      (is (= :branch (:ref-entity (:source-branch-id fields))))
      (is (= :timestamptz (:type (:source-timestamp fields))))
      (is (= :ref (:type (:target-branch-id fields))))
      (is (= :branch (:ref-entity (:target-branch-id fields))))
      (is (= :timestamptz (:type (:target-timestamp fields))))
      (is (= :timestamptz (:type (:created-at fields)))))))


(deftest fn-version-entity-test
  (testing "fn-version entity has correct fields for 2-entity schema"
    (let [schema (vds/build-schema (malli/create-builder))
          fields (ds/entity-fields schema :fn-version)]
      ;; Core references
      (is (= :ref (:type (:fn-id fields))))
      (is (= :fn (:ref-entity (:fn-id fields))))
      (is (= :ref (:type (:branch-id fields))))
      (is (= :branch (:ref-entity (:branch-id fields))))

      ;; fn data fields (versioned snapshot)
      (is (= :text (:type (:name fields))))
      (is (true? (:nullable? (:name fields))))
      (is (= :uuid (:type (:parent-id fields))))
      (is (true? (:nullable? (:parent-id fields))))
      (is (= :enum (:type (:return-type fields))))
      (is (true? (:nullable? (:return-type fields))))
      (is (= :text (:type (:impl-hash fields))))
      (is (true? (:nullable? (:impl-hash fields))))

      ;; Timestamps
      (is (= :timestamptz (:type (:created-at fields))))
      (is (= :timestamptz (:type (:deleted-at fields))))
      (is (true? (:nullable? (:deleted-at fields)))))))


(deftest arg-version-entity-test
  (testing "arg-version entity has correct fields for 2-entity schema"
    (let [schema (vds/build-schema (malli/create-builder))
          fields (ds/entity-fields schema :arg-version)]
      ;; Core references
      (is (= :ref (:type (:arg-id fields))))
      (is (= :arg (:ref-entity (:arg-id fields))))
      (is (= :ref (:type (:branch-id fields))))
      (is (= :branch (:ref-entity (:branch-id fields))))
      (is (= :ref (:type (:fn-id fields))))
      (is (= :fn (:ref-entity (:fn-id fields))))

      ;; arg data fields (versioned snapshot)
      (is (= :uuid (:type (:via-fn-id fields))))
      (is (true? (:nullable? (:via-fn-id fields))))
      (is (= :uuid (:type (:source-id fields))))
      (is (true? (:nullable? (:source-id fields))))
      (is (= :jsonb (:type (:value fields))))
      (is (true? (:nullable? (:value fields))))
      (is (= :uuid (:type (:ref-id fields))))
      (is (true? (:nullable? (:ref-id fields))))
      (is (= :text (:type (:name fields))))
      (is (true? (:nullable? (:name fields))))
      (is (= :enum (:type (:type fields))))
      (is (true? (:nullable? (:type fields))))
      (is (= :bool (:type (:required fields))))
      (is (true? (:nullable? (:required fields))))
      (is (= :bool (:type (:is-fn fields))))
      (is (true? (:nullable? (:is-fn fields))))

      ;; Timestamps
      (is (= :timestamptz (:type (:created-at fields))))
      (is (= :timestamptz (:type (:deleted-at fields))))
      (is (true? (:nullable? (:deleted-at fields)))))))


(deftest versioned-entities-constant-test
  (testing "versioned-entities contains all versioning entity names"
    (is (= #{:branch
             :branch-merge
             :fn-version
             :arg-version}
           vds/versioned-entities))))


(deftest version-entity-for-test
  (testing "maps base entities to their version entities (2-entity schema)"
    (is (= :fn-version (vds/version-entity-for :fn)))
    (is (= :arg-version (vds/version-entity-for :arg)))
    (is (nil? (vds/version-entity-for :branch)) "branch is not versioned")
    (is (nil? (vds/version-entity-for :branch-merge)) "branch-merge is not versioned")))


(deftest version-id-field-for-test
  (testing "maps base entities to their id field in version entity"
    (is (= :fn-id (vds/version-id-field-for :fn)))
    (is (= :arg-id (vds/version-id-field-for :arg)))))


(deftest extend-builder-test
  (testing "extend-builder returns builder, not schema"
    (let [builder (-> (malli/create-builder)
                      (vds/extend-builder))]
      (is (satisfies? ds/DataSchemaBuilder builder)))))


(deftest chainable-with-other-extensions-test
  (testing "extend-builder can be chained after graph-data-schema extend-builder"
    (let [gds-ns (requiring-resolve 'graphden.schema.graph.schema/extend-builder)
          builder (-> (malli/create-builder)
                      (gds-ns)
                      (vds/extend-builder))]
      (is (satisfies? ds/DataSchemaBuilder builder))
      (let [schema (ds/build builder)]
        (is (some #{:fn} (ds/entities schema)))
        (is (some #{:arg} (ds/entities schema)))
        (is (some #{:branch} (ds/entities schema)))
        (is (some #{:fn-version} (ds/entities schema)))
        (is (some #{:arg-version} (ds/entities schema)))))))
