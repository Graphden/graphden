(ns graphden.schema.versioned.interface
  "Versioned data schema definition.

   Extends graph-data-schema with entities for Git-like versioning:
   - branch: named branch with optional parent (base-branch-id)
   - branch-merge: merge record making source branch versions visible in target
   - fn-version: append-only version history for fn entities
   - fn-schema-version: append-only version history for fn-schema entities
   - arg-schema-version: append-only version history for arg-schema entities
   - fn-arg-version: append-only version history for fn-arg entities
   - fn-usage-version: append-only version history for fn-usage entities

   Two-table pattern: stable identity (id only) + version table (data + branch_id + created_at).
   Version tables are append-only: each change adds a new record.

   See docs/current-schema.dbml for the full versioning design and resolution algorithm."
  (:require
    [graphden.schema.graph.interface :as gds]
    [graphden.schema.protocol.interface :as ds]))


;; === Stable UUIDs for versioned schema elements ===
;;
;; See graph-data-schema for explanation of UUID stability.
;; IMPORTANT: Never change these UUIDs!

;; Entity UUIDs
(def ^:private branch-entity-uuid
  #uuid "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private branch-merge-entity-uuid
  #uuid "b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e")


(def ^:private fn-version-entity-uuid
  #uuid "c3d4e5f6-a7b8-4c9d-0e1f-2a3b4c5d6e7f")


(def ^:private fn-schema-version-entity-uuid
  #uuid "d4e5f6a7-b8c9-4d0e-1f2a-3b4c5d6e7f8a")


(def ^:private arg-schema-version-entity-uuid
  #uuid "e5f6a7b8-c9d0-4e1f-2a3b-4c5d6e7f8a9b")


(def ^:private fn-arg-version-entity-uuid
  #uuid "f6a7b8c9-d0e1-4f2a-3b4c-5d6e7f8a9b0c")


(def ^:private fn-usage-version-entity-uuid
  #uuid "a7b8c9d0-e1f2-4a3b-4c5d-6e7f8a9b0c1d")


;; === Field UUIDs for :branch ===

(def ^:private branch-name-field-uuid
  #uuid "01020304-0506-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private branch-base-branch-id-field-uuid
  #uuid "02030405-0607-4b8c-9d0e-1f2a3b4c5d6e")


(def ^:private branch-created-at-field-uuid
  #uuid "03040506-0708-4c9d-0e1f-2a3b4c5d6e7f")


;; === Field UUIDs for :branch-merge ===

(def ^:private branch-merge-source-branch-id-field-uuid
  #uuid "11121314-1516-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private branch-merge-source-timestamp-field-uuid
  #uuid "12131415-1617-4b8c-9d0e-1f2a3b4c5d6e")


(def ^:private branch-merge-target-branch-id-field-uuid
  #uuid "13141516-1718-4c9d-0e1f-2a3b4c5d6e7f")


(def ^:private branch-merge-target-timestamp-field-uuid
  #uuid "14151617-1819-4d0e-1f2a-3b4c5d6e7f8a")


(def ^:private branch-merge-created-at-field-uuid
  #uuid "15161718-1920-4e1f-2a3b-4c5d6e7f8a9b")


;; === Field UUIDs for :fn-version ===

(def ^:private fn-version-fn-id-field-uuid
  #uuid "21222324-2526-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private fn-version-branch-id-field-uuid
  #uuid "22232425-2627-4b8c-9d0e-1f2a3b4c5d6e")


(def ^:private fn-version-name-field-uuid
  #uuid "23242526-2728-4c9d-0e1f-2a3b4c5d6e7f")


(def ^:private fn-version-fn-schema-id-field-uuid
  #uuid "24252627-2829-4d0e-1f2a-3b4c5d6e7f8a")


(def ^:private fn-version-created-at-field-uuid
  #uuid "25262728-2930-4e1f-2a3b-4c5d6e7f8a9b")


;; === Field UUIDs for :fn-schema-version ===

(def ^:private fn-schema-version-fn-schema-id-field-uuid
  #uuid "31323334-3536-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private fn-schema-version-branch-id-field-uuid
  #uuid "32333435-3637-4b8c-9d0e-1f2a3b4c5d6e")


(def ^:private fn-schema-version-name-field-uuid
  #uuid "33343536-3738-4c9d-0e1f-2a3b4c5d6e7f")


(def ^:private fn-schema-version-returned-type-field-uuid
  #uuid "34353637-3839-4d0e-1f2a-3b4c5d6e7f8a")


(def ^:private fn-schema-version-base-fn-name-field-uuid
  #uuid "35363738-3940-4e1f-2a3b-4c5d6e7f8a9b")


(def ^:private fn-schema-version-impl-hash-field-uuid
  #uuid "36373839-4041-4f2a-3b4c-5d6e7f8a9b0c")


(def ^:private fn-schema-version-created-at-field-uuid
  #uuid "37383940-4142-4a3b-4c5d-6e7f8a9b0c1d")


;; === Field UUIDs for :arg-schema-version ===

(def ^:private arg-schema-version-arg-schema-id-field-uuid
  #uuid "41424344-4546-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private arg-schema-version-branch-id-field-uuid
  #uuid "42434445-4647-4b8c-9d0e-1f2a3b4c5d6e")


(def ^:private arg-schema-version-name-field-uuid
  #uuid "43444546-4748-4c9d-0e1f-2a3b4c5d6e7f")


(def ^:private arg-schema-version-type-field-uuid
  #uuid "44454647-4849-4d0e-1f2a-3b4c5d6e7f8a")


(def ^:private arg-schema-version-required-field-uuid
  #uuid "45464748-4950-4e1f-2a3b-4c5d6e7f8a9b")


(def ^:private arg-schema-version-created-at-field-uuid
  #uuid "46474849-5051-4f2a-3b4c-5d6e7f8a9b0c")


;; === Field UUIDs for :fn-arg-version ===

(def ^:private fn-arg-version-fn-arg-id-field-uuid
  #uuid "51525354-5556-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private fn-arg-version-branch-id-field-uuid
  #uuid "52535455-5657-4b8c-9d0e-1f2a3b4c5d6e")


(def ^:private fn-arg-version-fn-id-field-uuid
  #uuid "53545556-5758-4c9d-0e1f-2a3b4c5d6e7f")


(def ^:private fn-arg-version-arg-schema-id-field-uuid
  #uuid "54555657-5859-4d0e-1f2a-3b4c5d6e7f8a")


(def ^:private fn-arg-version-arg-value-id-field-uuid
  #uuid "55565758-5960-4e1f-2a3b-4c5d6e7f8a9b")


(def ^:private fn-arg-version-created-at-field-uuid
  #uuid "56575859-6061-4f2a-3b4c-5d6e7f8a9b0c")


;; === Field UUIDs for :fn-usage-version ===

(def ^:private fn-usage-version-fn-usage-id-field-uuid
  #uuid "61626364-6566-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private fn-usage-version-branch-id-field-uuid
  #uuid "62636465-6667-4b8c-9d0e-1f2a3b4c5d6e")


(def ^:private fn-usage-version-fn-id-field-uuid
  #uuid "63646566-6768-4c9d-0e1f-2a3b4c5d6e7f")


(def ^:private fn-usage-version-name-field-uuid
  #uuid "64656667-6869-4d0e-1f2a-3b4c5d6e7f8a")


(def ^:private fn-usage-version-owner-fn-id-field-uuid
  #uuid "65666768-6970-4e1f-2a3b-4c5d6e7f8a9b")


(def ^:private fn-usage-version-created-at-field-uuid
  #uuid "66676869-7071-4f2a-3b4c-5d6e7f8a9b0c")


;; === Public API ===

(def versioned-entities
  "Set of entity names that are versioning-specific (not part of base graph schema)."
  #{:branch
    :branch-merge
    :fn-version
    :fn-schema-version
    :arg-schema-version
    :fn-arg-version
    :fn-usage-version})


(def version-entity-for
  "Map from base entity name to its version entity name.
   Only versioned entities are included."
  {:fn :fn-version
   :fn-schema :fn-schema-version
   :arg-schema :arg-schema-version
   :fn-arg :fn-arg-version
   :fn-usage :fn-usage-version})


(def version-id-field-for
  "Map from base entity name to the field name in version entity that references it.
   E.g., :fn -> :fn-id means fn-version has a :fn-id field referencing fn.id."
  {:fn :fn-id
   :fn-schema :fn-schema-id
   :arg-schema :arg-schema-id
   :fn-arg :fn-arg-id
   :fn-usage :fn-usage-id})


(defn extend-builder
  "Extends a builder (that already has graph-data-schema entities) with versioning entities.
   Returns the builder for further extension or finalization.

   Expects :fn, :fn-schema, :arg-schema, :fn-arg, :call-site-arg entities to be defined.
   Can be chained with cache-data-schema/extend-builder in any order."
  [builder]
  (-> builder
      ;; branch: named branch with optional parent
      (ds/add-entity :branch branch-entity-uuid
                     {:name {:uuid branch-name-field-uuid
                             :type :text}
                      :base-branch-id {:uuid branch-base-branch-id-field-uuid
                                       :type :ref :ref-entity :branch
                                       :nullable? true}
                      :created-at {:uuid branch-created-at-field-uuid
                                   :type :timestamptz}})
      (ds/add-constraint :branch {:type :unique :fields [:name]})

      ;; branch-merge: single record per merge, no version records copied
      (ds/add-entity :branch-merge branch-merge-entity-uuid
                     {:source-branch-id {:uuid branch-merge-source-branch-id-field-uuid
                                         :type :ref :ref-entity :branch}
                      :source-timestamp {:uuid branch-merge-source-timestamp-field-uuid
                                         :type :timestamptz}
                      :target-branch-id {:uuid branch-merge-target-branch-id-field-uuid
                                         :type :ref :ref-entity :branch}
                      :target-timestamp {:uuid branch-merge-target-timestamp-field-uuid
                                         :type :timestamptz}
                      :created-at {:uuid branch-merge-created-at-field-uuid
                                   :type :timestamptz}})

      ;; fn-version: append-only version history for fn
      (ds/add-entity :fn-version fn-version-entity-uuid
                     {:fn-id {:uuid fn-version-fn-id-field-uuid
                              :type :ref :ref-entity :fn}
                      :branch-id {:uuid fn-version-branch-id-field-uuid
                                  :type :ref :ref-entity :branch}
                      :name {:uuid fn-version-name-field-uuid
                             :type :text}
                      :fn-schema-id {:uuid fn-version-fn-schema-id-field-uuid
                                     :type :uuid}
                      :created-at {:uuid fn-version-created-at-field-uuid
                                   :type :timestamptz}})
      (ds/add-constraint :fn-version {:type :unique :fields [:fn-id :branch-id :created-at]})

      ;; fn-schema-version: append-only version history for fn-schema
      (ds/add-entity :fn-schema-version fn-schema-version-entity-uuid
                     {:fn-schema-id {:uuid fn-schema-version-fn-schema-id-field-uuid
                                     :type :ref :ref-entity :fn-schema}
                      :branch-id {:uuid fn-schema-version-branch-id-field-uuid
                                  :type :ref :ref-entity :branch}
                      :name {:uuid fn-schema-version-name-field-uuid
                             :type :text}
                      :returned-type {:uuid fn-schema-version-returned-type-field-uuid
                                      :type :enum :enum-name :value-kind}
                      :base-fn-name {:uuid fn-schema-version-base-fn-name-field-uuid
                                     :type :text
                                     :nullable? true}
                      :impl-hash {:uuid fn-schema-version-impl-hash-field-uuid
                                  :type :text
                                  :nullable? true}
                      :created-at {:uuid fn-schema-version-created-at-field-uuid
                                   :type :timestamptz}})
      (ds/add-constraint :fn-schema-version {:type :unique :fields [:fn-schema-id :branch-id :created-at]})

      ;; arg-schema-version: append-only version history for arg-schema
      (ds/add-entity :arg-schema-version arg-schema-version-entity-uuid
                     {:arg-schema-id {:uuid arg-schema-version-arg-schema-id-field-uuid
                                      :type :ref :ref-entity :arg-schema}
                      :branch-id {:uuid arg-schema-version-branch-id-field-uuid
                                  :type :ref :ref-entity :branch}
                      :name {:uuid arg-schema-version-name-field-uuid
                             :type :text}
                      :type {:uuid arg-schema-version-type-field-uuid
                             :type :enum :enum-name :value-kind}
                      :required {:uuid arg-schema-version-required-field-uuid
                                 :type :bool}
                      :created-at {:uuid arg-schema-version-created-at-field-uuid
                                   :type :timestamptz}})
      (ds/add-constraint :arg-schema-version {:type :unique :fields [:arg-schema-id :branch-id :created-at]})

      ;; fn-arg-version: append-only version history for fn-arg
      (ds/add-entity :fn-arg-version fn-arg-version-entity-uuid
                     {:fn-arg-id {:uuid fn-arg-version-fn-arg-id-field-uuid
                                  :type :ref :ref-entity :fn-arg}
                      :branch-id {:uuid fn-arg-version-branch-id-field-uuid
                                  :type :ref :ref-entity :branch}
                      :fn-id {:uuid fn-arg-version-fn-id-field-uuid
                              :type :ref :ref-entity :fn}
                      :arg-schema-id {:uuid fn-arg-version-arg-schema-id-field-uuid
                                      :type :ref :ref-entity :arg-schema}
                      :arg-value-id {:uuid fn-arg-version-arg-value-id-field-uuid
                                     :type :ref :ref-entity :arg-value}
                      :created-at {:uuid fn-arg-version-created-at-field-uuid
                                   :type :timestamptz}})
      (ds/add-constraint :fn-arg-version {:type :unique :fields [:fn-arg-id :branch-id :created-at]})

      ;; fn-usage-version: append-only version history for fn-usage
      (ds/add-entity :fn-usage-version fn-usage-version-entity-uuid
                     {:fn-usage-id {:uuid fn-usage-version-fn-usage-id-field-uuid
                                    :type :ref :ref-entity :fn-usage}
                      :branch-id {:uuid fn-usage-version-branch-id-field-uuid
                                  :type :ref :ref-entity :branch}
                      :fn-id {:uuid fn-usage-version-fn-id-field-uuid
                              :type :ref :ref-entity :fn}
                      :name {:uuid fn-usage-version-name-field-uuid
                             :type :text}
                      :owner-fn-id {:uuid fn-usage-version-owner-fn-id-field-uuid
                                    :type :uuid
                                    :nullable? true}
                      :created-at {:uuid fn-usage-version-created-at-field-uuid
                                   :type :timestamptz}})
      (ds/add-constraint :fn-usage-version {:type :unique :fields [:fn-usage-id :branch-id :created-at]})))


(defn build-schema
  "Builds the complete schema with both graph and versioning entities.
   Returns a built DataSchema instance."
  [builder]
  (-> builder
      (gds/extend-builder)
      (extend-builder)
      (ds/build)))
