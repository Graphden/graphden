(ns graphden.schema.versioned.schema
  "Versioned data schema - Git-like versioning for fn and arg entities.

   Extends graph-schema with:
   - branch: named branch with optional parent
   - branch-merge: merge record
   - fn-version: version history for fn
   - arg-version: version history for arg"
  (:require
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.protocol.protocol :as ds]))


;; Entity UUIDs
(def ^:private branch-entity-uuid
  #uuid "a1b2c3d4-e5f6-4789-8c9d-0e1f2a3b4c5d")


(def ^:private branch-merge-entity-uuid
  #uuid "b2c3d4e5-f6a7-4890-9d0e-1f2a3b4c5d6e")


(def ^:private fn-version-entity-uuid
  #uuid "c3d4e5f6-a7b8-4901-0e1f-2a3b4c5d6e7f")


(def ^:private arg-version-entity-uuid
  #uuid "d4e5f6a7-b8c9-4012-1f2a-3b4c5d6e7f8a")


;; Field UUIDs for :branch
(def ^:private branch-name-field-uuid
  #uuid "01020304-0506-4123-8c9d-0e1f2a3b4c5d")


(def ^:private branch-base-branch-id-field-uuid
  #uuid "02030405-0607-4234-9d0e-1f2a3b4c5d6e")


(def ^:private branch-created-at-field-uuid
  #uuid "03040506-0708-4345-0e1f-2a3b4c5d6e7f")


;; Field UUIDs for :branch-merge
(def ^:private branch-merge-source-branch-id-field-uuid
  #uuid "11121314-1516-4456-8c9d-0e1f2a3b4c5d")


(def ^:private branch-merge-source-timestamp-field-uuid
  #uuid "12131415-1617-4567-9d0e-1f2a3b4c5d6e")


(def ^:private branch-merge-target-branch-id-field-uuid
  #uuid "13141516-1718-4678-0e1f-2a3b4c5d6e7f")


(def ^:private branch-merge-target-timestamp-field-uuid
  #uuid "14151617-1819-4789-1f2a-3b4c5d6e7f8a")


(def ^:private branch-merge-created-at-field-uuid
  #uuid "15161718-1920-4890-2a3b-4c5d6e7f8a9b")


;; Field UUIDs for :fn-version
(def ^:private fn-version-fn-id-field-uuid
  #uuid "21222324-2526-4901-8c9d-0e1f2a3b4c5d")


(def ^:private fn-version-branch-id-field-uuid
  #uuid "22232425-2627-4012-9d0e-1f2a3b4c5d6e")


(def ^:private fn-version-name-field-uuid
  #uuid "23242526-2728-4123-0e1f-2a3b4c5d6e7f")


(def ^:private fn-version-return-type-field-uuid
  #uuid "25262728-2930-4345-2a3b-4c5d6e7f8a9b")


(def ^:private fn-version-impl-hash-field-uuid
  #uuid "26272829-3031-4456-3b4c-5d6e7f8a9b0c")


(def ^:private fn-version-created-at-field-uuid
  #uuid "27282930-3132-4567-4c5d-6e7f8a9b0c1d")


(def ^:private fn-version-deleted-at-field-uuid
  #uuid "28293031-3233-4678-5d6e-7f8a9b0c1d2e")


;; Field UUIDs for :arg-version
(def ^:private arg-version-arg-id-field-uuid
  #uuid "31323334-3536-4789-8c9d-0e1f2a3b4c5d")


(def ^:private arg-version-branch-id-field-uuid
  #uuid "32333435-3637-4890-9d0e-1f2a3b4c5d6e")


(def ^:private arg-version-fn-id-field-uuid
  #uuid "33343536-3738-4901-0e1f-2a3b4c5d6e7f")


(def ^:private arg-version-via-fn-id-field-uuid
  #uuid "34353637-3839-4012-1f2a-3b4c5d6e7f8a")


(def ^:private arg-version-source-id-field-uuid
  #uuid "35363738-3940-4123-2a3b-4c5d6e7f8a9b")


(def ^:private arg-version-value-field-uuid
  #uuid "36373839-4041-4234-3b4c-5d6e7f8a9b0c")


(def ^:private arg-version-ref-id-field-uuid
  #uuid "37383940-4142-4345-4c5d-6e7f8a9b0c1d")


(def ^:private arg-version-name-field-uuid
  #uuid "38394041-4243-4456-5d6e-7f8a9b0c1d2e")


(def ^:private arg-version-type-field-uuid
  #uuid "39404142-4344-4567-6e7f-8a9b0c1d2e3f")


(def ^:private arg-version-required-field-uuid
  #uuid "40414243-4445-4678-7f8a-9b0c1d2e3f4a")


(def ^:private arg-version-is-fn-field-uuid
  #uuid "41424344-4546-4789-8a9b-0c1d2e3f4a5b")


(def ^:private arg-version-next-arg-id-field-uuid
  #uuid "44454647-4849-4a0b-1c2d-3e4f5a6b7c8d")


(def ^:private arg-version-prev-arg-id-field-uuid
  #uuid "45464748-494a-4b1c-2d3e-4f5a6b7c8d9e")


(def ^:private arg-version-created-at-field-uuid
  #uuid "42434445-4647-4890-9b0c-1d2e3f4a5b6c")


(def ^:private arg-version-deleted-at-field-uuid
  #uuid "43444546-4748-4901-0c1d-2e3f4a5b6c7d")


(def versioned-entities
  #{:branch :branch-merge :fn-version :arg-version})


(def version-entity-for
  {:fn :fn-version
   :arg :arg-version})


(def version-id-field-for
  {:fn :fn-id
   :arg :arg-id})


(defn extend-builder
  "Extends a builder with versioning entities."
  [builder]
  (-> builder
      ;; branch
      (ds/add-entity :branch branch-entity-uuid
                     {:name {:uuid branch-name-field-uuid
                             :type :text}
                      :base-branch-id {:uuid branch-base-branch-id-field-uuid
                                       :type :ref :ref-entity :branch
                                       :nullable? true}
                      :created-at {:uuid branch-created-at-field-uuid
                                   :type :timestamptz}})
      (ds/add-constraint :branch {:type :unique :fields [:name]})

      ;; branch-merge
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

      ;; fn-version
      (ds/add-entity :fn-version fn-version-entity-uuid
                     {:fn-id {:uuid fn-version-fn-id-field-uuid
                              :type :ref :ref-entity :fn}
                      :branch-id {:uuid fn-version-branch-id-field-uuid
                                  :type :ref :ref-entity :branch}
                      :name {:uuid fn-version-name-field-uuid
                             :type :text
                             :nullable? true}
                      :return-type {:uuid fn-version-return-type-field-uuid
                                    :type :enum :enum-name :value-kind
                                    :nullable? true}
                      :impl-hash {:uuid fn-version-impl-hash-field-uuid
                                  :type :text
                                  :nullable? true}
                      :created-at {:uuid fn-version-created-at-field-uuid
                                   :type :timestamptz}
                      :deleted-at {:uuid fn-version-deleted-at-field-uuid
                                   :type :timestamptz
                                   :nullable? true}})
      (ds/add-constraint :fn-version {:type :unique :fields [:fn-id :branch-id :created-at]})

      ;; arg-version
      (ds/add-entity :arg-version arg-version-entity-uuid
                     {:arg-id {:uuid arg-version-arg-id-field-uuid
                               :type :ref :ref-entity :arg}
                      :branch-id {:uuid arg-version-branch-id-field-uuid
                                  :type :ref :ref-entity :branch}
                      :fn-id {:uuid arg-version-fn-id-field-uuid
                              :type :ref :ref-entity :fn}
                      :via-fn-id {:uuid arg-version-via-fn-id-field-uuid
                                  :type :uuid
                                  :nullable? true}
                      :source-id {:uuid arg-version-source-id-field-uuid
                                  :type :uuid
                                  :nullable? true}
                      :value {:uuid arg-version-value-field-uuid
                              :type :jsonb
                              :nullable? true}
                      :ref-id {:uuid arg-version-ref-id-field-uuid
                               :type :uuid
                               :nullable? true}
                      :name {:uuid arg-version-name-field-uuid
                             :type :text
                             :nullable? true}
                      :type {:uuid arg-version-type-field-uuid
                             :type :enum :enum-name :value-kind
                             :nullable? true}
                      :required {:uuid arg-version-required-field-uuid
                                 :type :bool
                                 :nullable? true}
                      :is-fn {:uuid arg-version-is-fn-field-uuid
                              :type :bool
                              :nullable? true}
                      :next-arg-id {:uuid arg-version-next-arg-id-field-uuid
                                    :type :uuid
                                    :nullable? true}
                      :prev-arg-id {:uuid arg-version-prev-arg-id-field-uuid
                                    :type :uuid
                                    :nullable? true}
                      :created-at {:uuid arg-version-created-at-field-uuid
                                   :type :timestamptz}
                      :deleted-at {:uuid arg-version-deleted-at-field-uuid
                                   :type :timestamptz
                                   :nullable? true}})
      (ds/add-constraint :arg-version {:type :unique :fields [:arg-id :branch-id :created-at]})))


(defn build-schema
  "Builds the complete schema with graph and versioning entities."
  [builder]
  (-> builder
      (gds/extend-builder)
      (extend-builder)
      (ds/build)))
