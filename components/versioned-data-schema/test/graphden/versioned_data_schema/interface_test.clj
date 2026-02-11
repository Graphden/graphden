(ns graphden.versioned-data-schema.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.core :as malli]
    [graphden.versioned-data-schema.interface :as vds]))


(deftest build-schema-test
  (testing "builds schema with graph and versioning entities"
    (let [builder (malli/create-builder)
          schema (vds/build-schema builder)]

      (testing "includes graph entities"
        (is (some #{:fn} (ds/entities schema)))
        (is (some #{:fn-schema} (ds/entities schema)))
        (is (some #{:arg-schema} (ds/entities schema)))
        (is (some #{:arg-value} (ds/entities schema)))
        (is (some #{:fn-arg} (ds/entities schema)))
        (is (some #{:call-site} (ds/entities schema)))
        (is (some #{:call-site-arg} (ds/entities schema))))

      (testing "includes versioning entities"
        (is (some #{:branch} (ds/entities schema)))
        (is (some #{:branch-merge} (ds/entities schema)))
        (is (some #{:fn-version} (ds/entities schema)))
        (is (some #{:fn-schema-version} (ds/entities schema)))
        (is (some #{:arg-schema-version} (ds/entities schema)))
        (is (some #{:fn-arg-version} (ds/entities schema)))
        (is (some #{:call-site-arg-version} (ds/entities schema))))

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
  (testing "fn-version entity has correct fields"
    (let [schema (vds/build-schema (malli/create-builder))
          fields (ds/entity-fields schema :fn-version)]
      (is (= :ref (:type (:fn-id fields))))
      (is (= :fn (:ref-entity (:fn-id fields))))
      (is (= :ref (:type (:branch-id fields))))
      (is (= :branch (:ref-entity (:branch-id fields))))
      (is (= :text (:type (:name fields))))
      (is (= :uuid (:type (:fn-schema-id fields))))
      (is (= :timestamptz (:type (:created-at fields)))))))


(deftest fn-schema-version-entity-test
  (testing "fn-schema-version entity has correct fields"
    (let [schema (vds/build-schema (malli/create-builder))
          fields (ds/entity-fields schema :fn-schema-version)]
      (is (= :ref (:type (:fn-schema-id fields))))
      (is (= :fn-schema (:ref-entity (:fn-schema-id fields))))
      (is (= :ref (:type (:branch-id fields))))
      (is (= :text (:type (:name fields))))
      (is (= :enum (:type (:returned-type fields))))
      (is (true? (:nullable? (:base-fn-name fields))))
      (is (true? (:nullable? (:impl-hash fields))))
      (is (= :timestamptz (:type (:created-at fields)))))))


(deftest arg-schema-version-entity-test
  (testing "arg-schema-version entity has correct fields"
    (let [schema (vds/build-schema (malli/create-builder))
          fields (ds/entity-fields schema :arg-schema-version)]
      (is (= :ref (:type (:arg-schema-id fields))))
      (is (= :arg-schema (:ref-entity (:arg-schema-id fields))))
      (is (= :ref (:type (:branch-id fields))))
      (is (= :text (:type (:name fields))))
      (is (= :enum (:type (:type fields))))
      (is (= :bool (:type (:required fields))))
      (is (= :timestamptz (:type (:created-at fields)))))))


(deftest fn-arg-version-entity-test
  (testing "fn-arg-version entity has correct fields"
    (let [schema (vds/build-schema (malli/create-builder))
          fields (ds/entity-fields schema :fn-arg-version)]
      (is (= :ref (:type (:fn-arg-id fields))))
      (is (= :fn-arg (:ref-entity (:fn-arg-id fields))))
      (is (= :ref (:type (:branch-id fields))))
      (is (= :ref (:type (:fn-id fields))))
      (is (= :fn (:ref-entity (:fn-id fields))))
      (is (= :ref (:type (:arg-schema-id fields))))
      (is (= :ref (:type (:arg-value-id fields))))
      (is (= :timestamptz (:type (:created-at fields)))))))


(deftest call-site-arg-version-entity-test
  (testing "call-site-arg-version entity has correct fields"
    (let [schema (vds/build-schema (malli/create-builder))
          fields (ds/entity-fields schema :call-site-arg-version)]
      (is (= :ref (:type (:call-site-arg-id fields))))
      (is (= :call-site-arg (:ref-entity (:call-site-arg-id fields))))
      (is (= :ref (:type (:branch-id fields))))
      (is (= :ref (:type (:call-site-id fields))))
      (is (= :call-site (:ref-entity (:call-site-id fields))))
      (is (= :ref (:type (:arg-schema-id fields))))
      (is (= :ref (:type (:arg-value-id fields))))
      (is (= :timestamptz (:type (:created-at fields)))))))


(deftest versioned-entities-constant-test
  (testing "versioned-entities contains all versioning entity names"
    (is (= #{:branch
             :branch-merge
             :fn-version
             :fn-schema-version
             :arg-schema-version
             :fn-arg-version
             :call-site-arg-version}
           vds/versioned-entities))))


(deftest version-entity-for-test
  (testing "maps base entities to their version entities"
    (is (= :fn-version (vds/version-entity-for :fn)))
    (is (= :fn-schema-version (vds/version-entity-for :fn-schema)))
    (is (= :arg-schema-version (vds/version-entity-for :arg-schema)))
    (is (= :fn-arg-version (vds/version-entity-for :fn-arg)))
    (is (= :call-site-arg-version (vds/version-entity-for :call-site-arg)))
    (is (nil? (vds/version-entity-for :arg-value)))
    (is (nil? (vds/version-entity-for :call-site)))))


(deftest version-id-field-for-test
  (testing "maps base entities to their id field in version entity"
    (is (= :fn-id (vds/version-id-field-for :fn)))
    (is (= :fn-schema-id (vds/version-id-field-for :fn-schema)))
    (is (= :arg-schema-id (vds/version-id-field-for :arg-schema)))
    (is (= :fn-arg-id (vds/version-id-field-for :fn-arg)))
    (is (= :call-site-arg-id (vds/version-id-field-for :call-site-arg)))))


(deftest extend-builder-test
  (testing "extend-builder returns builder, not schema"
    (let [builder (-> (malli/create-builder)
                      (vds/extend-builder))]
      (is (satisfies? ds/DataSchemaBuilder builder)))))


(deftest chainable-with-other-extensions-test
  (testing "extend-builder can be chained after graph-data-schema extend-builder"
    (let [gds-ns (requiring-resolve 'graphden.graph-data-schema.interface/extend-builder)
          builder (-> (malli/create-builder)
                      (gds-ns)
                      (vds/extend-builder))]
      (is (satisfies? ds/DataSchemaBuilder builder))
      (let [schema (ds/build builder)]
        (is (some #{:fn} (ds/entities schema)))
        (is (some #{:branch} (ds/entities schema)))
        (is (some #{:fn-version} (ds/entities schema)))))))
