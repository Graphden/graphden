(ns graphden.schema.versioned.schema
  "Versioned schema — extends graph-schema with branch / version
   entities for Git-like versioning of mutable entities.

   Versioned (mutable):
   - fn               → fn-version
   - fn-slot          → fn-slot-version
   - binding          → binding-version
   - binding-list-item → binding-list-item-version

   Not versioned (immutable post-create):
   - slot — `(name, type-fn-id)` pair never changes; create new slot
            instead of mutating.
   - ns   — намespace structure rarely changes; not versioned (matches
            existing behavior).

   The old `arg-version` entity is **removed** — `arg` itself is gone
   from graph schema (replaced by slot/fn-slot/binding/binding-list-item)."
  (:require
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.protocol.protocol :as ds]))


;; =============================================================================
;; Entity UUIDs — branches + version entities
;; =============================================================================

(def ^:private branch-entity-uuid
  #uuid "a1b2c3d4-e5f6-4789-8c9d-0e1f2a3b4c5d")


(def ^:private branch-merge-entity-uuid
  #uuid "b2c3d4e5-f6a7-4890-9d0e-1f2a3b4c5d6e")


(def ^:private fn-version-entity-uuid
  #uuid "c3d4e5f6-a7b8-4901-0e1f-2a3b4c5d6e7f")


(def ^:private fn-slot-version-entity-uuid
  #uuid "0efeec22-0ef9-4f88-b3eb-11ce0205376e")


(def ^:private binding-version-entity-uuid
  #uuid "8adfb5a2-c194-4def-8fe3-2ddc5a06409d")


(def ^:private binding-list-item-version-entity-uuid
  #uuid "58d29309-4843-4156-bd2a-9669c715a6b4")


;; =============================================================================
;; Field UUIDs — :branch
;; =============================================================================

(def ^:private branch-name-field-uuid
  #uuid "01020304-0506-4123-8c9d-0e1f2a3b4c5d")


(def ^:private branch-base-branch-id-field-uuid
  #uuid "02030405-0607-4234-9d0e-1f2a3b4c5d6e")


(def ^:private branch-created-at-field-uuid
  #uuid "03040506-0708-4345-0e1f-2a3b4c5d6e7f")


(def ^:private branch-org-id-field-uuid
  ;; Tenant owner (§4 org-scoped branches). NULL ≡ public (`main`). Stamped by
  ;; OrgScopedStorage; the read-filter isolates a tenant's branches. Names are
  ;; unique PER ORG (`UNIQUE (org-id, name) NULLS NOT DISTINCT`) — distinct orgs
  ;; may reuse a name, with no cross-org collision / existence leak.
  #uuid "04050607-0809-4456-9f0a-3b4c5d6e7f80")


;; =============================================================================
;; Field UUIDs — :branch-merge
;; =============================================================================

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


;; =============================================================================
;; Field UUIDs — :fn-version
;; =============================================================================

(def ^:private fn-version-fn-id-field-uuid
  #uuid "21222324-2526-4901-8c9d-0e1f2a3b4c5d")


(def ^:private fn-version-branch-id-field-uuid
  #uuid "22232425-2627-4012-9d0e-1f2a3b4c5d6e")


(def ^:private fn-version-name-field-uuid
  #uuid "23242526-2728-4123-0e1f-2a3b4c5d6e7f")


(def ^:private fn-version-impl-hash-field-uuid
  #uuid "26272829-3031-4456-3b4c-5d6e7f8a9b0c")


(def ^:private fn-version-created-at-field-uuid
  #uuid "27282930-3132-4567-4c5d-6e7f8a9b0c1d")


(def ^:private fn-version-deleted-at-field-uuid
  #uuid "28293031-3233-4678-5d6e-7f8a9b0c1d2e")


(def ^:private fn-version-description-field-uuid
  #uuid "3c4d5e6f-7a8b-4c9d-0e1f-2a3b4c5d6e7f")


(def ^:private fn-version-constraint-field-uuid
  #uuid "31323334-3536-4789-8c9d-0e1f2a3b4c5d")


(def ^:private fn-version-base-fn-id-field-uuid
  #uuid "32333435-3637-4890-9d0e-1f2a3b4c5d6e")


(def ^:private fn-version-element-fn-id-field-uuid
  #uuid "33343536-3738-4901-0e1f-2a3b4c5d6e7f")


(def ^:private fn-version-return-type-fn-id-field-uuid
  #uuid "34353637-3839-4012-1f2a-3b4c5d6e7f8a")


(def ^:private fn-version-anonymous-hash-field-uuid
  #uuid "35363738-3940-4123-2a3b-4c5d6e7f8a9b")


;; Mirrors fn.expects-effects (sym `fn-expects-effects-field-uuid` in
;; the graph-data-schema). Must exist on the version mirror or the
;; versioned-storage decorator strips writes silently and reads come
;; back without the column.
(def ^:private fn-version-expects-effects-field-uuid
  #uuid "44a5c620-9e31-4d28-8b3a-6c1f5e9d2a47")


;; =============================================================================
;; Field UUIDs — :fn-slot-version
;; =============================================================================

(def ^:private fn-slot-version-fn-slot-id-field-uuid
  #uuid "36373839-4041-4234-3b4c-5d6e7f8a9b0c")


(def ^:private fn-slot-version-branch-id-field-uuid
  #uuid "37383940-4142-4345-4c5d-6e7f8a9b0c1d")


(def ^:private fn-slot-version-fn-id-field-uuid
  #uuid "38394041-4243-4456-5d6e-7f8a9b0c1d2e")


(def ^:private fn-slot-version-slot-id-field-uuid
  #uuid "39404142-4344-4567-6e7f-8a9b0c1d2e3f")


(def ^:private fn-slot-version-position-field-uuid
  #uuid "40414243-4445-4678-7f8a-9b0c1d2e3f4a")


(def ^:private fn-slot-version-created-at-field-uuid
  #uuid "42434445-4647-4890-9b0c-1d2e3f4a5b6c")


(def ^:private fn-slot-version-deleted-at-field-uuid
  #uuid "43444546-4748-4901-0c1d-2e3f4a5b6c7d")


;; =============================================================================
;; Field UUIDs — :binding-version
;; =============================================================================

(def ^:private binding-version-binding-id-field-uuid
  #uuid "44454647-4849-4a0b-1c2d-3e4f5a6b7c8d")


(def ^:private binding-version-branch-id-field-uuid
  #uuid "45464748-494a-4b1c-2d3e-4f5a6b7c8d9e")


(def ^:private binding-version-fn-id-field-uuid
  #uuid "4d5e6f7a-8b9c-4d0e-1f2a-3b4c5d6e7f8a")


(def ^:private binding-version-slot-id-field-uuid
  #uuid "e5607d87-1875-4cc6-a55d-bccee21807b5")


(def ^:private binding-version-value-field-uuid
  #uuid "51afa4af-fb8f-400b-8d26-6781fed40721")


(def ^:private binding-version-value-present-field-uuid
  #uuid "9f8c6e8d-56ee-4b82-a11b-15bc1bb491be")


(def ^:private binding-version-ref-fn-id-field-uuid
  #uuid "276ddd7a-4caa-4280-98b0-8fca584c3367")


(def ^:private binding-version-override-kind-field-uuid
  #uuid "3680c037-849c-46cb-a20c-d14680ea2435")


(def ^:private binding-version-rename-to-field-uuid
  #uuid "ca54868a-50f6-4aec-9d7b-518cb604c812")


(def ^:private binding-version-type-override-fn-id-field-uuid
  #uuid "063f3d28-2ecd-4b8a-a944-c005b72be75b")


(def ^:private binding-version-description-field-uuid
  #uuid "fda4de1c-2d46-4e6a-a233-bd9060af2b69")


(def ^:private binding-version-list-append-field-uuid
  #uuid "fb84cff4-6f91-4396-b607-1b34cff9a40e")


(def ^:private binding-version-list-closed-field-uuid
  #uuid "5bab550c-a484-4bd9-9ce5-b1df7549bc0e")


(def ^:private binding-version-terminal-field-uuid
  ;; Mirror of binding.:terminal (§4.3 seal) — must exist or VersionedStorage
  ;; strips the flag on write + reads come back without it.
  #uuid "8f4b2d19-6e3a-4c07-ab51-9d2f7a8c1e46")


(def ^:private binding-version-created-at-field-uuid
  #uuid "f4b5fd01-2cac-4db5-ba0b-b7c229b0b2a5")


(def ^:private binding-version-deleted-at-field-uuid
  #uuid "24b920a2-f4eb-4e7d-a7b8-12b634cbe372")


;; =============================================================================
;; Field UUIDs — :binding-list-item-version
;; =============================================================================

(def ^:private binding-list-item-version-item-id-field-uuid
  #uuid "8e44ded4-b721-4620-b3c2-54f76aaac6b9")


(def ^:private binding-list-item-version-branch-id-field-uuid
  #uuid "d5940cd0-6d02-4fee-a996-d65aea7cd198")


(def ^:private binding-list-item-version-binding-id-field-uuid
  #uuid "ed238e51-f6c6-45d5-8fd6-ae9554bfffe9")


(def ^:private binding-list-item-version-position-field-uuid
  #uuid "02c57f0c-5c93-4303-96a4-9ff7878c8be4")


(def ^:private binding-list-item-version-value-field-uuid
  #uuid "53b45480-882f-4ef1-81f7-6993d8c49d12")


(def ^:private binding-list-item-version-ref-fn-id-field-uuid
  #uuid "f544e4e7-bc24-4859-a99f-0b542e8311fd")


(def ^:private binding-list-item-version-literal-field-uuid
  #uuid "18875e04-1876-45f8-aae3-1dd8548458ec")


(def ^:private binding-list-item-version-created-at-field-uuid
  #uuid "6be2d727-9f48-4861-a50b-7aa040230880")


(def ^:private binding-list-item-version-deleted-at-field-uuid
  #uuid "e67deb49-ba44-4e3a-870f-f7eddaaffbbe")


;; =============================================================================
;; Versioned-entity registry
;; =============================================================================

(def versioned-entities
  #{:branch :branch-merge :fn-version :fn-slot-version
    :binding-version :binding-list-item-version})


(def version-entity-for
  {:fn               :fn-version
   :fn-slot          :fn-slot-version
   :binding          :binding-version
   :binding-list-item :binding-list-item-version})


(def version-id-field-for
  {:fn               :fn-id
   :fn-slot          :fn-slot-id
   :binding          :binding-id
   :binding-list-item :item-id})


;; =============================================================================
;; Schema
;; =============================================================================

(defn extend-builder
  "Extends a builder with versioning entities."
  [builder]
  (-> builder
      ;; -----------------------------------------------------------------
      ;; branch
      ;; -----------------------------------------------------------------
      (ds/add-entity :branch branch-entity-uuid
                     {:name {:uuid branch-name-field-uuid
                             :type :text}
                      :base-branch-id {:uuid branch-base-branch-id-field-uuid
                                       :type :ref :ref-entity :branch
                                       :nullable? true}
                      :created-at {:uuid branch-created-at-field-uuid
                                   :type :timestamptz}
                      ;; Tenant owner (§4). NULL ≡ public (main).
                      :org-id {:uuid branch-org-id-field-uuid
                               :type :text
                               :nullable? true}})
      (ds/add-constraint :branch {:type :unique :fields [:org-id :name]
                                  :nulls-not-distinct? true})

      ;; -----------------------------------------------------------------
      ;; branch-merge
      ;; -----------------------------------------------------------------
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

      ;; -----------------------------------------------------------------
      ;; fn-version
      ;; -----------------------------------------------------------------
      (ds/add-entity :fn-version fn-version-entity-uuid
                     {:fn-id {:uuid fn-version-fn-id-field-uuid
                              :type :ref :ref-entity :fn}
                      :branch-id {:uuid fn-version-branch-id-field-uuid
                                  :type :ref :ref-entity :branch}
                      :name {:uuid fn-version-name-field-uuid
                             :type :text :nullable? true}
                      :impl-hash {:uuid fn-version-impl-hash-field-uuid
                                  :type :text :nullable? true}
                      :description {:uuid fn-version-description-field-uuid
                                    :type :text :nullable? true}
                      :constraint {:uuid fn-version-constraint-field-uuid
                                   :type :jsonb :nullable? true}
                      :base-fn-id {:uuid fn-version-base-fn-id-field-uuid
                                   :type :uuid :nullable? true}
                      :element-fn-id {:uuid fn-version-element-fn-id-field-uuid
                                      :type :uuid :nullable? true}
                      :return-type-fn-id {:uuid fn-version-return-type-fn-id-field-uuid
                                          :type :uuid :nullable? true}
                      :anonymous-hash {:uuid fn-version-anonymous-hash-field-uuid
                                       :type :text :nullable? true}
                      :expects-effects {:uuid fn-version-expects-effects-field-uuid
                                        :type :jsonb :nullable? true}
                      :created-at {:uuid fn-version-created-at-field-uuid
                                   :type :timestamptz}
                      :deleted-at {:uuid fn-version-deleted-at-field-uuid
                                   :type :timestamptz :nullable? true}})
      (ds/add-constraint :fn-version
                         {:type :unique :fields [:fn-id :branch-id :created-at]})

      ;; -----------------------------------------------------------------
      ;; fn-slot-version
      ;; -----------------------------------------------------------------
      (ds/add-entity :fn-slot-version fn-slot-version-entity-uuid
                     {:fn-slot-id {:uuid fn-slot-version-fn-slot-id-field-uuid
                                   :type :ref :ref-entity :fn-slot}
                      :branch-id {:uuid fn-slot-version-branch-id-field-uuid
                                  :type :ref :ref-entity :branch}
                      :fn-id {:uuid fn-slot-version-fn-id-field-uuid
                              :type :uuid}
                      :slot-id {:uuid fn-slot-version-slot-id-field-uuid
                                :type :uuid}
                      :position {:uuid fn-slot-version-position-field-uuid
                                 :type :int}
                      :created-at {:uuid fn-slot-version-created-at-field-uuid
                                   :type :timestamptz}
                      :deleted-at {:uuid fn-slot-version-deleted-at-field-uuid
                                   :type :timestamptz :nullable? true}})
      (ds/add-constraint :fn-slot-version
                         {:type :unique :fields [:fn-slot-id :branch-id :created-at]})

      ;; -----------------------------------------------------------------
      ;; binding-version
      ;; -----------------------------------------------------------------
      (ds/add-entity :binding-version binding-version-entity-uuid
                     {:binding-id {:uuid binding-version-binding-id-field-uuid
                                   :type :ref :ref-entity :binding}
                      :branch-id {:uuid binding-version-branch-id-field-uuid
                                  :type :ref :ref-entity :branch}
                      :fn-id {:uuid binding-version-fn-id-field-uuid
                              :type :uuid}
                      :slot-id {:uuid binding-version-slot-id-field-uuid
                                :type :uuid}
                      :value {:uuid binding-version-value-field-uuid
                              :type :jsonb :nullable? true}
                      ;; Mirrors :binding's :value-present — see
                      ;; schema/graph/schema.clj for the rationale.
                      :value-present {:uuid binding-version-value-present-field-uuid
                                      :type :bool :nullable? true}
                      ;; :indexed? — drives the version-side of the reverse-ref
                      ;; lookup in `:ref-owner-bindings` (find-fn-usages / delete
                      ;; ref-check). Not a `:ref` (no FK — the target fn may be
                      ;; deleted while a version row lingers), so it needs the
                      ;; explicit index flag.
                      :ref-fn-id {:uuid binding-version-ref-fn-id-field-uuid
                                  :type :uuid :nullable? true :indexed? true}
                      :override-kind {:uuid binding-version-override-kind-field-uuid
                                      :type :enum :enum-name :override-kind
                                      :nullable? true}
                      ;; `:rename-to` retired in Phase 6e (mirror of the
                      ;; main binding entity). See retire-field call below.
                      :type-override-fn-id {:uuid binding-version-type-override-fn-id-field-uuid
                                            :type :uuid :nullable? true}
                      :description {:uuid binding-version-description-field-uuid
                                    :type :text :nullable? true}
                      :list-append {:uuid binding-version-list-append-field-uuid
                                    :type :bool :nullable? true}
                      :list-closed {:uuid binding-version-list-closed-field-uuid
                                    :type :bool :nullable? true}
                      :terminal {:uuid binding-version-terminal-field-uuid
                                 :type :bool :nullable? true}
                      :created-at {:uuid binding-version-created-at-field-uuid
                                   :type :timestamptz}
                      :deleted-at {:uuid binding-version-deleted-at-field-uuid
                                   :type :timestamptz :nullable? true}})
      (ds/add-constraint :binding-version
                         {:type :unique :fields [:binding-id :branch-id :created-at]})

      ;; -----------------------------------------------------------------
      ;; binding-list-item-version
      ;; -----------------------------------------------------------------
      (ds/add-entity :binding-list-item-version binding-list-item-version-entity-uuid
                     {:item-id {:uuid binding-list-item-version-item-id-field-uuid
                                :type :ref :ref-entity :binding-list-item}
                      :branch-id {:uuid binding-list-item-version-branch-id-field-uuid
                                  :type :ref :ref-entity :branch}
                      :binding-id {:uuid binding-list-item-version-binding-id-field-uuid
                                   :type :uuid}
                      :position {:uuid binding-list-item-version-position-field-uuid
                                 :type :int}
                      :value {:uuid binding-list-item-version-value-field-uuid
                              :type :jsonb :nullable? true}
                      ;; :indexed? — version-side reverse-ref lookup (see the
                      ;; :binding-version :ref-fn-id note above).
                      :ref-fn-id {:uuid binding-list-item-version-ref-fn-id-field-uuid
                                  :type :uuid :nullable? true :indexed? true}
                      :literal {:uuid binding-list-item-version-literal-field-uuid
                                :type :bool :nullable? true}
                      :created-at {:uuid binding-list-item-version-created-at-field-uuid
                                   :type :timestamptz}
                      :deleted-at {:uuid binding-list-item-version-deleted-at-field-uuid
                                   :type :timestamptz :nullable? true}})
      (ds/add-constraint :binding-list-item-version
                         {:type :unique :fields [:item-id :branch-id :created-at]})

      ;; -----------------------------------------------------------------
      ;; Retired fields (Phase 6e)
      ;; -----------------------------------------------------------------
      (ds/retire-field :binding-version :rename-to binding-version-rename-to-field-uuid)))


(defn build-schema
  "Builds the complete schema with graph and versioning entities."
  [builder]
  (-> builder
      (gds/extend-builder)
      (extend-builder)
      (ds/build)))
