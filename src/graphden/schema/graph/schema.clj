(ns graphden.schema.graph.schema
  "Graph data schema — fn/slot/fn-slot/binding model.

   Five entities form the base graph:
   - ns                — namespace (organization, name uniqueness scope).
   - fn                — единая сущность для функций И типов.
                         type'ы — fn-rows без impl, специализированные через
                         base-fn-id (refinement), element-fn-id (list), или
                         просто несущие fn-slot rows (record / composite).
   - slot              — атомарная (name, type-fn-id) пара. Шарится между
                         многими fn'ами через fn-slot junction.
                         Immutable post-create.
   - fn-slot           — junction: fn ⊃ slots с порядком.
                         Описывает 'parameters / fields этой fn'.
   - binding           — per-fn customization конкретного слота:
                         value/ref-binding, rename, type-override,
                         terminal seal, list-append/closed flags.
   - binding-list-item — items для list-typed slot binding'ов
                         (ordered, indexable).

   ## Role determined by field-presence (no `kind` discriminator)

   | parent-fn-ids | impl-hash | base-fn-id | element-fn-id | constraint | fn-slot rows | Role |
   |---|---|---|---|---|---|---|
   | empty | NOT NULL | NULL | NULL | NULL | * | base-fn (Clojure impl) |
   | empty | NULL | NULL | NULL | NULL | NOT empty | record-type (auto-builder) |
   | empty | NULL | NOT NULL | NULL | NOT NULL | empty | refinement-type |
   | empty | NULL | NULL | NOT NULL | NULL | empty | list-type |
   | empty | NULL | NULL | NULL | NULL | empty | primitive (boot data) |
   | NOT empty | * | * | * | * | * | composed fn-def |

   ## Old `arg` model — REMOVED

   The previous `arg` entity with `source-id` / `prev-arg-id` /
   `next-arg-id` chain is gone. Inheritance no longer materializes
   per-slot rows on each child fn — free args are computed by walking
   parent-fn-ids chain and subtracting bound slots. Sequences live as
   list-typed slots with `binding-list-item` rows."
  (:require
    [graphden.schema.fields.types :as ft]
    [graphden.schema.protocol.protocol :as ds]))


;; =============================================================================
;; Enums
;; =============================================================================

(def ^:private value-kind-enum-uuid
  #uuid "b79e6e8b-8aff-4188-862b-d8a85ef4fcdf")


(def ^:private value-kind-values
  {:null        #uuid "c703ffd9-6401-4c49-9ca3-a280f6aac8ba"
   :uuid        #uuid "3a83af1b-f15c-421d-a5f1-f13db07deb72"
   :text        #uuid "cf26384f-d093-461d-9268-b42b8fd6eae6"
   :int         #uuid "154d3c4f-8d11-4592-9e24-5c40176cc5a7"
   :bool        #uuid "7497d750-67aa-4b55-8477-8323a9ab7761"
   :numeric     #uuid "f7a6728b-5ac6-4e1a-8bdb-ddc240cc059d"
   :timestamptz #uuid "e4476a32-3e93-4333-b0e5-964b9b19bea1"
   :jsonb       #uuid "b1b15bb9-a458-4337-9241-2a33e1ef25ea"
   :bytes       #uuid "2dcadfbd-800f-4b7b-bbcc-82b2afcf9f86"
   :any         #uuid "a3d7e8f1-9b2c-4d5e-8f6a-1c2d3e4f5a6b"
   :fn          #uuid "b4e8f9a2-0c3d-5e6f-9a7b-2d3e4f5a6b7c"
   :sequence    #uuid "9d1b3f8c-7a2e-4c5d-8e3f-1a2b4c6d8e0f"
   :keyword     #uuid "5e2a4b6c-8d1f-4e7a-9b3c-7f5d8e0a2c4d"})


;; Override-kind enum — policy для binding'а value/ref:
;;   :fixed   — descendants не могут override этот binding (default).
;;   :default — это «дефолт», descendant может полностью заменить.
(def ^:private override-kind-enum-uuid
  #uuid "8199ca93-0a28-403a-8625-69c6a801d0c4")


(def ^:private override-kind-values
  {:fixed   #uuid "902c5068-8162-493d-b9d0-3590efb4d30c"
   :default #uuid "e0f08934-335a-4724-9ac0-de7256c4a55d"})


;; =============================================================================
;; Entity UUIDs
;; =============================================================================

(def ^:private ns-entity-uuid
  #uuid "d4e5f6a7-b8c9-4d0e-1f2a-3b4c5d6e7f80")


(def ^:private fn-entity-uuid
  #uuid "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private slot-entity-uuid
  #uuid "f2bc63ef-7661-4c53-98dc-ef06c004ad60")


(def ^:private fn-slot-entity-uuid
  #uuid "f52634f0-e203-49bf-b0e9-a762d7e924e7")


(def ^:private binding-entity-uuid
  #uuid "a855e0aa-0efa-4acb-804c-715be50de146")


(def ^:private binding-list-item-entity-uuid
  #uuid "05bbcb6c-da00-473a-b62a-217a7be2e38b")


;; =============================================================================
;; Field UUIDs — :ns
;; =============================================================================

(def ^:private ns-name-field-uuid
  #uuid "e5f6a7b8-1234-4c9d-0e1f-aabbccddeeff")


(def ^:private ns-parent-id-field-uuid
  #uuid "f6a7b8c9-2345-4d0e-1f2a-aabbccddeeff")


(def ^:private ns-description-field-uuid
  #uuid "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")


;; =============================================================================
;; Field UUIDs — :fn
;; =============================================================================

(def ^:private fn-name-field-uuid
  #uuid "c3d4e5f6-a7b8-4c9d-0e1f-2a3b4c5d6e7f")


(def ^:private fn-namespace-id-field-uuid
  #uuid "a7b8c9d0-3456-4e1f-2a3b-aabbccddeeff")


(def ^:private fn-parent-ids-field-uuid
  #uuid "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private fn-impl-hash-field-uuid
  #uuid "f6a7b8c9-d0e1-4f2a-3b4c-5d6e7f8a9b0c")


(def ^:private fn-description-field-uuid
  #uuid "1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e")


;; Refinement-type predicate. Set ⇒ this fn-row IS a refinement-type.
;; At runtime — implicit impl validates the single `:value` slot binding
;; against this constraint, throws `:refinement/violated` on miss.
;; Mutually exclusive with `impl-hash` and `parent-ids` (enforced loader/CRUD).
(def ^:private fn-constraint-field-uuid
  #uuid "2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e90")


;; Refinement base — what type we're refining. Required when `constraint`
;; is set. Walks via this FK to compute the fully-resolved structural
;; rich-type `[:refine <base> <constraint>]`.
(def ^:private fn-base-fn-id-field-uuid
  #uuid "db40b8a7-6a3e-4c0f-8f3d-a1b1ea20c1f2")


;; List-element type. Set ⇒ this fn-row IS a list-type, with elements
;; of the referenced type. Slots referencing this fn carry `[:list T]`.
(def ^:private fn-element-fn-id-field-uuid
  #uuid "2b872fc4-951b-445e-ae82-571bf7057ad0")


;; Declared return type. Replaces the old `return-type` enum column.
;; FK → fn (which represents the type). NULL for fn-defs whose return
;; is purely computed from parent chain / impl.
(def ^:private fn-return-type-fn-id-field-uuid
  #uuid "a9fbce25-cde0-4f8f-855d-65799ca5a747")


;; For anonymous composite types (inline `:input {:foo :int}` без name)
;; — hash от sorted (slot-id, position) пар. UNIQUE INDEX по этому полю
;; обеспечивает dedup: одинаковая shape → один type-row.
(def ^:private fn-anonymous-hash-field-uuid
  #uuid "a373c531-1ace-4b62-a9ed-789a83988a21")


;; Authored `:expects-effects` declaration — the set of effects the
;; fn-def's *author* claims this fn produces. The type-checker compares
;; this against `:effects` computed from the impl/body and flags drift
;; (effects appeared without being declared) or over-declaration
;; (declared but never produced). Stored as a JSONB array of keywords
;; (e.g. `["db" "io"]`) and surfaced both via /api/types and the editor.
;; Loader writes from `fns.edn` on sync; UI may overwrite per-row.
(def ^:private fn-expects-effects-field-uuid
  #uuid "d52a7c10-3b94-4f0d-9c1e-7a4d8b6f2e15")


;; =============================================================================
;; Field UUIDs — :slot
;; =============================================================================

(def ^:private slot-name-field-uuid
  #uuid "45567a17-c458-440e-984d-5a0335e79e88")


(def ^:private slot-type-fn-id-field-uuid
  #uuid "81ec21e2-bec2-450c-a4cc-1e8e40944616")


(def ^:private slot-description-field-uuid
  #uuid "f47d60f1-c0d4-4b32-91a3-a8972cbc4e33")


(def ^:private slot-required-field-uuid
  #uuid "1ddc55ee-cb3d-4877-a07a-72ee47d24e51")


;; Phase 6 — link a renamed slot back to its source slot in the parent
;; closure. When set, the row is a "renamed view" of `source-slot-id`
;; under a new `:name`; descendants binding the new name target THIS
;; slot's id (via the standard `slot-id(owner-fn-id, slot-name)` UUIDv5
;; scheme), and the resolver walks the chain to find the original
;; type / inherited binding when needed.
;;
;; The corresponding `:rename-to` text on `binding` becomes redundant
;; once every renamed slot carries this FK — backfill + drop is the
;; final step of Phase 6.
(def ^:private slot-source-slot-id-field-uuid
  #uuid "0a4e0c11-3f4f-5a8e-9d1c-d8e3a7b2f5c1")


;; =============================================================================
;; Field UUIDs — :fn-slot (junction)
;; =============================================================================

(def ^:private fn-slot-fn-id-field-uuid
  #uuid "28f8574b-5fd2-44c9-9a0c-1a06ae85b21f")


(def ^:private fn-slot-slot-id-field-uuid
  #uuid "c06dfbba-4b2a-422b-a48f-5104ce31b718")


(def ^:private fn-slot-position-field-uuid
  #uuid "e67dc181-42f3-4d02-adfc-894cabe01a0f")


;; =============================================================================
;; Field UUIDs — :binding
;; =============================================================================

(def ^:private binding-fn-id-field-uuid
  #uuid "5541a2be-757f-4e53-ac89-27c6ca7e1b84")


(def ^:private binding-slot-id-field-uuid
  #uuid "7ee8a91d-6ec9-4e03-8ed7-700f0fb53aaa")


(def ^:private binding-value-field-uuid
  #uuid "6459f00a-76ef-4fc3-a331-d222dc130982")


(def ^:private binding-ref-fn-id-field-uuid
  #uuid "d16294e8-cc37-4a74-9a75-d8e865a9660a")


(def ^:private binding-override-kind-field-uuid
  #uuid "4c41a63b-c4cb-47e0-818a-bf7d129a5066")


(def ^:private binding-rename-to-field-uuid
  #uuid "db3f560b-4b5b-4486-bd72-3cb4626d73d7")


(def ^:private binding-type-override-fn-id-field-uuid
  #uuid "fb360739-96aa-46cf-878a-c53398460a72")


(def ^:private binding-description-field-uuid
  #uuid "0db6164f-137e-47bd-9be8-786a4d4ffe07")


(def ^:private binding-terminal-field-uuid
  #uuid "0a593316-21ae-4c6d-9131-8f13a001930e")


(def ^:private binding-list-append-field-uuid
  #uuid "509e045b-4ac9-4a6b-ac53-20f02b2e1f8d")


(def ^:private binding-list-closed-field-uuid
  #uuid "9cff0b5c-36dc-4527-8ee2-2943466a2520")


(def ^:private binding-required-field-uuid
  #uuid "54c53941-0b30-4020-b701-530d4d043d63")


;; =============================================================================
;; Field UUIDs — :binding-list-item
;; =============================================================================

(def ^:private binding-list-item-binding-id-field-uuid
  #uuid "b1ce8d68-08bf-4872-be1d-60dcb03137d6")


(def ^:private binding-list-item-position-field-uuid
  #uuid "151911f8-be74-4943-b7eb-6f453849c0bc")


(def ^:private binding-list-item-value-field-uuid
  #uuid "84dffedc-2d78-48a9-89d9-9f81c93e91f3")


(def ^:private binding-list-item-ref-fn-id-field-uuid
  #uuid "59ab3d7b-b6e8-4194-977b-cc5c199c6065")


(def ^:private binding-list-item-literal-field-uuid
  #uuid "0931c1b5-3c19-4323-927d-e180182e9e0a")


;; =============================================================================
;; Enum builders
;; =============================================================================

(defn- value-kind-enum-values
  []
  (into [{:uuid (get value-kind-values :null) :value :null}
         {:uuid (get value-kind-values :any) :value :any}
         {:uuid (get value-kind-values :fn) :value :fn}]
        (map (fn [t] {:uuid (get value-kind-values t) :value t})
             ft/supported-types)))


(defn- override-kind-enum-values
  []
  (mapv (fn [[k uuid]] {:uuid uuid :value k}) override-kind-values))


;; =============================================================================
;; Schema
;; =============================================================================

(defn extend-builder
  "Extends a builder with graph schema entities."
  [builder]
  (-> builder
      ;; Enums
      (ds/add-enum :value-kind value-kind-enum-uuid (value-kind-enum-values))
      (ds/add-enum :override-kind override-kind-enum-uuid (override-kind-enum-values))

      ;; -----------------------------------------------------------------
      ;; ns: namespace entity
      ;; -----------------------------------------------------------------
      (ds/add-entity :ns ns-entity-uuid
                     {:name {:uuid ns-name-field-uuid
                             :type :text}
                      :parent-id {:uuid ns-parent-id-field-uuid
                                  :type :ref
                                  :ref-entity :ns
                                  :nullable? true}
                      :description {:uuid ns-description-field-uuid
                                    :type :text
                                    :nullable? true}})
      (ds/add-constraint :ns {:type :unique :fields [:parent-id :name]})

      ;; -----------------------------------------------------------------
      ;; fn: function entity (also represents types — see ns-doc).
      ;; -----------------------------------------------------------------
      (ds/add-entity :fn fn-entity-uuid
                     {:name {:uuid fn-name-field-uuid
                             :type :text
                             :nullable? true}
                      :namespace-id {:uuid fn-namespace-id-field-uuid
                                     :type :ref
                                     :ref-entity :ns
                                     :nullable? true}
                      :parent-ids {:uuid fn-parent-ids-field-uuid
                                   :type :ref-many
                                   :ref-entity :fn
                                   :nullable? true}
                      :impl-hash {:uuid fn-impl-hash-field-uuid
                                  :type :text
                                  :nullable? true}
                      :description {:uuid fn-description-field-uuid
                                    :type :text
                                    :nullable? true}
                      ;; Refinement-type marker.
                      :constraint {:uuid fn-constraint-field-uuid
                                   :type :jsonb
                                   :nullable? true}
                      ;; Refinement base type (FK→fn).
                      :base-fn-id {:uuid fn-base-fn-id-field-uuid
                                   :type :ref
                                   :ref-entity :fn
                                   :nullable? true}
                      ;; List-type element (FK→fn).
                      :element-fn-id {:uuid fn-element-fn-id-field-uuid
                                      :type :ref
                                      :ref-entity :fn
                                      :nullable? true}
                      ;; Declared return type (FK→fn). Replaces old
                      ;; `return-type` enum column.
                      :return-type-fn-id {:uuid fn-return-type-fn-id-field-uuid
                                          :type :ref
                                          :ref-entity :fn
                                          :nullable? true}
                      ;; Hash for anonymous composite dedup.
                      :anonymous-hash {:uuid fn-anonymous-hash-field-uuid
                                       :type :text
                                       :nullable? true}
                      ;; Authored effect-set declaration (drift checker
                      ;; warns when computed effects diverge from this).
                      :expects-effects {:uuid fn-expects-effects-field-uuid
                                        :type :jsonb
                                        :nullable? true}})
      (ds/add-constraint :fn {:type :unique :fields [:namespace-id :name]})
      (ds/add-constraint :fn {:type :unique :fields [:anonymous-hash]})

      ;; -----------------------------------------------------------------
      ;; slot: атомарная (name, type) пара.
      ;; Immutable — изменение типа = создание нового slot.
      ;; -----------------------------------------------------------------
      (ds/add-entity :slot slot-entity-uuid
                     {:name {:uuid slot-name-field-uuid
                             :type :text}
                      :type-fn-id {:uuid slot-type-fn-id-field-uuid
                                   :type :ref
                                   :ref-entity :fn}
                      :required {:uuid slot-required-field-uuid
                                 :type :bool
                                 :nullable? true}
                      :description {:uuid slot-description-field-uuid
                                    :type :text
                                    :nullable? true}
                      ;; Renamed-view marker — when set, this slot is a
                      ;; new identity for `source-slot-id` exposed under
                      ;; a different `:name`. Composed fn-defs use this
                      ;; to surface inherited slots under their own
                      ;; vocabulary; descendants then bind by the new
                      ;; name and the binding lands on this slot's id
                      ;; via the deterministic UUIDv5 scheme.
                      :source-slot-id {:uuid slot-source-slot-id-field-uuid
                                       :type :ref
                                       :ref-entity :slot
                                       :nullable? true}})

      ;; -----------------------------------------------------------------
      ;; fn-slot: junction many-to-many. fn ⊃ slots с порядком.
      ;; Описывает 'parameters / fields этой fn'.
      ;; -----------------------------------------------------------------
      (ds/add-entity :fn-slot fn-slot-entity-uuid
                     {:fn-id {:uuid fn-slot-fn-id-field-uuid
                              :type :ref
                              :ref-entity :fn}
                      :slot-id {:uuid fn-slot-slot-id-field-uuid
                                :type :ref
                                :ref-entity :slot}
                      :position {:uuid fn-slot-position-field-uuid
                                 :type :int}})
      (ds/add-constraint :fn-slot {:type :unique :fields [:fn-id :slot-id]})

      ;; -----------------------------------------------------------------
      ;; binding: per-fn customization конкретного слота.
      ;; Mutually-exclusive groups:
      ;;   value/ref-binding (value xor ref-fn-id) + override-kind
      ;;   rename (rename-to)
      ;;   type-override (type-override-fn-id)
      ;;   per-level metadata (description, terminal)
      ;;   list-specific markers (list-append, list-closed)
      ;; Application-side constraint enforces «at most one
      ;; non-nil from {value, ref-fn-id}».
      ;; -----------------------------------------------------------------
      (ds/add-entity :binding binding-entity-uuid
                     {:fn-id {:uuid binding-fn-id-field-uuid
                              :type :ref
                              :ref-entity :fn}
                      :slot-id {:uuid binding-slot-id-field-uuid
                                :type :ref
                                :ref-entity :slot}
                      :value {:uuid binding-value-field-uuid
                              :type :jsonb
                              :nullable? true}
                      :ref-fn-id {:uuid binding-ref-fn-id-field-uuid
                                  :type :ref
                                  :ref-entity :fn
                                  :nullable? true}
                      :override-kind {:uuid binding-override-kind-field-uuid
                                      :type :enum
                                      :enum-name :override-kind
                                      :nullable? true}
                      ;; `:rename-to` retired in Phase 6e — see retire-field
                      ;; call after the entity declaration. The text was
                      ;; replaced by slot.source-slot-id (FK).
                      :type-override-fn-id {:uuid binding-type-override-fn-id-field-uuid
                                            :type :ref
                                            :ref-entity :fn
                                            :nullable? true}
                      :description {:uuid binding-description-field-uuid
                                    :type :text
                                    :nullable? true}
                      :terminal {:uuid binding-terminal-field-uuid
                                 :type :bool
                                 :nullable? true}
                      :list-append {:uuid binding-list-append-field-uuid
                                    :type :bool
                                    :nullable? true}
                      :list-closed {:uuid binding-list-closed-field-uuid
                                    :type :bool
                                    :nullable? true}
                      :required {:uuid binding-required-field-uuid
                                 :type :bool
                                 :nullable? true}})
      (ds/add-constraint :binding {:type :unique :fields [:fn-id :slot-id]})

      ;; -----------------------------------------------------------------
      ;; binding-list-item: ordered items для list-typed slot binding'ов.
      ;; -----------------------------------------------------------------
      (ds/add-entity :binding-list-item binding-list-item-entity-uuid
                     {:binding-id {:uuid binding-list-item-binding-id-field-uuid
                                   :type :ref
                                   :ref-entity :binding}
                      :position {:uuid binding-list-item-position-field-uuid
                                 :type :int}
                      :value {:uuid binding-list-item-value-field-uuid
                              :type :jsonb
                              :nullable? true}
                      :ref-fn-id {:uuid binding-list-item-ref-fn-id-field-uuid
                                  :type :ref
                                  :ref-entity :fn
                                  :nullable? true}
                      :literal {:uuid binding-list-item-literal-field-uuid
                                :type :bool
                                :nullable? true}})
      (ds/add-constraint :binding-list-item
                         {:type :unique :fields [:binding-id :position]})

      ;; -----------------------------------------------------------------
      ;; Retired fields (Phase 6e — drop binding.rename-to)
      ;; -----------------------------------------------------------------
      ;; The `slot.source-slot-id` FK is the canonical home for renames
      ;; now. The legacy text column has had no readers since Phase 6c
      ;; and no writers since Phase 6d; mark it retired so the migration
      ;; framework issues DROP COLUMN on next deploy.
      (ds/retire-field :binding :rename-to binding-rename-to-field-uuid)))


(defn build-schema
  "Builds the graph data schema."
  [builder]
  (-> builder
      (extend-builder)
      (ds/build)))
