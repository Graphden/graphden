(ns graphden.schema.versioned.schema-test
  "Tests for the versioned schema in the slot/fn-slot/binding model.

   Versioned (mutable):
     fn → fn-version
     fn-slot → fn-slot-version
     binding → binding-version
     binding-list-item → binding-list-item-version

   NOT versioned (immutable post-create):
     slot — `(name, type-fn-id)` pair never changes."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.malli.core :as malli]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.versioned.schema :as vds]))


(deftest build-schema-test
  (testing "builds schema with graph and versioning entities"
    (let [builder (malli/create-builder)
          schema (vds/build-schema builder)]

      (testing "includes graph entities"
        (is (some #{:fn} (ds/entities schema)))
        (is (some #{:slot} (ds/entities schema)))
        (is (some #{:fn-slot} (ds/entities schema)))
        (is (some #{:binding} (ds/entities schema)))
        (is (some #{:binding-list-item} (ds/entities schema))))

      (testing "includes versioning entities (and explicitly NOT slot-version)"
        (is (some #{:branch} (ds/entities schema)))
        (is (some #{:branch-merge} (ds/entities schema)))
        (is (some #{:fn-version} (ds/entities schema)))
        (is (some #{:fn-slot-version} (ds/entities schema)))
        (is (some #{:binding-version} (ds/entities schema)))
        (is (some #{:binding-list-item-version} (ds/entities schema)))
        (is (not-any? #{:slot-version} (ds/entities schema))
            "slot is immutable, so no slot-version entity"))

      (testing "includes value-kind enum"
        (is (contains? (ds/enums schema) :value-kind))))))


(deftest branch-entity-test
  (testing "branch entity has correct fields"
    (let [schema (vds/build-schema (malli/create-builder))
          fields (ds/entity-fields schema :branch)]
      (is (contains? fields :name))
      (is (contains? fields :base-branch-id))
      (is (contains? fields :created-at))
      (is (= :ref (:type (:base-branch-id fields))))
      (is (= :branch (:ref-entity (:base-branch-id fields))))
      (is (true? (:nullable? (:base-branch-id fields)))))))


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
  (testing "fn-version mirrors the new fn entity (no return-type enum column)"
    (let [schema (vds/build-schema (malli/create-builder))
          fields (ds/entity-fields schema :fn-version)]
      (is (= :ref (:type (:fn-id fields))))
      (is (= :fn (:ref-entity (:fn-id fields))))
      (is (= :ref (:type (:branch-id fields))))
      (is (= :branch (:ref-entity (:branch-id fields))))
      (is (= :text (:type (:name fields))))
      (is (true? (:nullable? (:name fields))))
      (is (= :jsonb (:type (:constraint fields))))
      (is (= :uuid (:type (:base-fn-id fields))))
      (is (= :uuid (:type (:element-fn-id fields))))
      (is (= :uuid (:type (:return-type-fn-id fields))))
      (is (= :text (:type (:anonymous-hash fields))))
      (is (= :timestamptz (:type (:created-at fields))))
      (is (true? (:nullable? (:deleted-at fields)))))))


(deftest binding-version-entity-test
  (testing "binding-version mirrors binding fields"
    (let [schema (vds/build-schema (malli/create-builder))
          fields (ds/entity-fields schema :binding-version)]
      (is (= :ref (:type (:binding-id fields))))
      (is (= :binding (:ref-entity (:binding-id fields))))
      (is (= :ref (:type (:branch-id fields))))
      (is (= :uuid (:type (:fn-id fields))))
      (is (= :uuid (:type (:slot-id fields))))
      (is (= :jsonb (:type (:value fields))))
      (is (nil? (:override-kind fields))
          "mirror column retired with its base (audit-2 2b)")
      (is (= :timestamptz (:type (:created-at fields)))))))


(deftest versioned-entities-constant-test
  (testing "versioned-entities lists every versioned entity name"
    (is (= #{:branch :branch-merge :fn-version :fn-slot-version
             :binding-version :binding-list-item-version}
           vds/versioned-entities))))


(deftest version-entity-for-test
  (testing "maps base entities to their version entities"
    (is (= :fn-version (vds/version-entity-for :fn)))
    (is (= :fn-slot-version (vds/version-entity-for :fn-slot)))
    (is (= :binding-version (vds/version-entity-for :binding)))
    (is (= :binding-list-item-version (vds/version-entity-for :binding-list-item)))
    (is (nil? (vds/version-entity-for :slot)) "slot is immutable, not versioned")
    (is (nil? (vds/version-entity-for :branch)))))


(deftest version-id-field-for-test
  (testing "maps base entities to their id field in their version table"
    (is (= :fn-id (vds/version-id-field-for :fn)))
    (is (= :fn-slot-id (vds/version-id-field-for :fn-slot)))
    (is (= :binding-id (vds/version-id-field-for :binding)))
    (is (= :item-id (vds/version-id-field-for :binding-list-item)))))


;; =============================================================================
;; Mirror derivation — the single-source guarantee
;; =============================================================================

(deftest version-data-fields-derived-test
  (testing "version-data-fields derive from the SAME source as the mirror
            entities — pinned snapshots guard the identity/versioned split"
    (is (= #{:name :description :constraint :base-fn-id :element-fn-id
             :return-type-fn-id :anonymous-hash :expects-effects
             :lambda-params}
           (vds/version-data-fields :fn)))
    (is (= #{:fn-id :slot-id :position} (vds/version-data-fields :fn-slot)))
    (is (= #{:fn-id :slot-id :value :value-present :ref-fn-id
             :type-override-fn-id :description :list-append :list-closed
             :terminal :required :resolver-fn-id}
           (vds/version-data-fields :binding)))
    (is (= #{:binding-id :position :value :ref-fn-id :literal}
           (vds/version-data-fields :binding-list-item)))))


(deftest mirror-derivation-invariants-test
  (let [schema (vds/build-schema (malli/create-builder))]
    (testing "every versioned data field has a mirror column (the old
              silent-drop hole is structurally closed)"
      (doseq [base [:fn :fn-slot :binding :binding-list-item]]
        (let [mirror-fields (ds/entity-fields schema (vds/version-entity-for base))]
          (doseq [f (vds/version-data-fields base)]
            (is (contains? mirror-fields f)
                (str base "/" f " must have a mirror column"))))))
    (testing "identity-level base fields stay OFF the mirror"
      (let [fields (ds/entity-fields schema :fn-version)]
        (doseq [f [:namespace-id :parent-ids :org-id :branch-local?]]
          (is (not (contains? fields f))
              (str ":fn-version must not mirror identity-level " f)))))
    (testing "base refs demote to bare uuids on the mirror, nullability kept"
      (let [fields (ds/entity-fields schema :binding-version)]
        (is (= :uuid (:type (:ref-fn-id fields))))
        (is (nil? (:ref-entity (:ref-fn-id fields))))
        (is (true? (:nullable? (:ref-fn-id fields))))))
    (testing "mirror-only tweaks apply (version-side reverse-ref indexes)"
      (is (true? (:indexed? (:ref-fn-id (ds/entity-fields schema :binding-version)))))
      (is (true? (:indexed? (:ref-fn-id (ds/entity-fields schema :binding-list-item-version)))))
      (is (true? (:indexed? (:name (ds/entity-fields schema :fn-version))))
          "fn-version.name keeps the base's index — check-fn-name-collision!
           bounds its candidate query on it"))))


(deftest derive-version-fields-fails-loud-test
  (testing "a new base field without a pinned mirror uuid throws at build
            time instead of silently dropping version writes"
    (let [ex (try (vds/derive-version-fields
                    :fn
                    {:brand-new {:uuid (random-uuid) :type :text :nullable? true}}
                    {:identity-fields #{} :uuids {}}
                    {})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= {:entity :fn :field :brand-new} (ex-data ex))))
    (testing "…unless declared identity-level"
      (is (= {} (vds/derive-version-fields
                  :fn
                  {:brand-new {:uuid (random-uuid) :type :text}}
                  {:identity-fields #{:brand-new} :uuids {}}
                  {}))))
    (testing "the REVERSE drift fails loud too: a pinned uuid whose base
              field no longer exists (or went identity-level) throws"
      (let [ex (try (vds/derive-version-fields
                      :fn
                      {}
                      {:identity-fields #{} :uuids {:ghost (random-uuid)}}
                      {})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= {:entity :fn :orphans [:ghost]} (ex-data ex)))))))


(deftest extend-builder-test
  (testing "extend-builder returns builder, not schema"
    (let [builder (-> (malli/create-builder)
                      (vds/extend-builder))]
      (is (satisfies? ds/DataSchemaBuilder builder)))))


(deftest chainable-with-other-extensions-test
  (testing "extend-builder can be chained after graph schema extend-builder"
    (let [gds-extend (requiring-resolve 'graphden.schema.graph.schema/extend-builder)
          builder (-> (malli/create-builder)
                      (gds-extend)
                      (vds/extend-builder))]
      (is (satisfies? ds/DataSchemaBuilder builder))
      (let [schema (ds/build builder)]
        (is (some #{:fn} (ds/entities schema)))
        (is (some #{:slot} (ds/entities schema)))
        (is (some #{:branch} (ds/entities schema)))
        (is (some #{:fn-version} (ds/entities schema)))
        (is (some #{:binding-version} (ds/entities schema)))))))
