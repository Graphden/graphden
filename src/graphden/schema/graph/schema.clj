(ns graphden.schema.graph.schema
  "Graph data schema - minimal 2-entity model for function composition.

   Two entities:
   - fn: function (base or composed)
   - arg: argument (primary or inherited)

   Design principles:
   - fn with empty parent-ids = base-fn (Clojure implementation)
   - fn with parent-ids = composed fn (inherits behavior, supports multiple inheritance)
   - fn with name=nil = local fn (scoped, not globally visible)
   - arg without source-id = primary argument (defines interface)
   - arg with source-id = inherited/forwarded argument
   - arg with value/ref-id both nil = exposed (part of fn interface)
   - Inheritance: nil fields inherit from source/parent
   - Strict wins: required=true, is-fn=true propagate up inheritance chain"
  (:require
    [graphden.schema.fields.types :as ft]
    [graphden.schema.protocol.protocol :as ds]))


;; Enum UUIDs
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
   :sequence    #uuid "9d1b3f8c-7a2e-4c5d-8e3f-1a2b4c6d8e0f"})


;; Entity UUIDs
(def ^:private ns-entity-uuid
  #uuid "d4e5f6a7-b8c9-4d0e-1f2a-3b4c5d6e7f80")


(def ^:private fn-entity-uuid
  #uuid "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private arg-entity-uuid
  #uuid "b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e")


;; Field UUIDs for :ns
(def ^:private ns-name-field-uuid
  #uuid "e5f6a7b8-1234-4c9d-0e1f-aabbccddeeff")


(def ^:private ns-parent-id-field-uuid
  #uuid "f6a7b8c9-2345-4d0e-1f2a-aabbccddeeff")


(def ^:private ns-description-field-uuid
  #uuid "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")


;; Field UUIDs for :fn
(def ^:private fn-name-field-uuid
  #uuid "c3d4e5f6-a7b8-4c9d-0e1f-2a3b4c5d6e7f")


(def ^:private fn-namespace-id-field-uuid
  #uuid "a7b8c9d0-3456-4e1f-2a3b-aabbccddeeff")


(def ^:private fn-parent-ids-field-uuid
  #uuid "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private fn-return-type-field-uuid
  #uuid "e5f6a7b8-c9d0-4e1f-2a3b-4c5d6e7f8a9b")


(def ^:private fn-impl-hash-field-uuid
  #uuid "f6a7b8c9-d0e1-4f2a-3b4c-5d6e7f8a9b0c")


(def ^:private fn-description-field-uuid
  #uuid "1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e")


;; Field UUIDs for :arg
(def ^:private arg-fn-id-field-uuid
  #uuid "a7b8c9d0-e1f2-4a3b-4c5d-6e7f8a9b0c1d")


(def ^:private arg-via-fn-id-field-uuid
  #uuid "b8c9d0e1-f2a3-4b4c-5d6e-7f8a9b0c1d2e")


(def ^:private arg-source-id-field-uuid
  #uuid "c9d0e1f2-a3b4-4c5d-6e7f-8a9b0c1d2e3f")


(def ^:private arg-value-field-uuid
  #uuid "d0e1f2a3-b4c5-4d6e-7f8a-9b0c1d2e3f4a")


(def ^:private arg-ref-id-field-uuid
  #uuid "e1f2a3b4-c5d6-4e7f-8a9b-0c1d2e3f4a5b")


(def ^:private arg-name-field-uuid
  #uuid "f2a3b4c5-d6e7-4f8a-9b0c-1d2e3f4a5b6c")


(def ^:private arg-type-field-uuid
  #uuid "a3b4c5d6-e7f8-4a9b-0c1d-2e3f4a5b6c7d")


(def ^:private arg-required-field-uuid
  #uuid "b4c5d6e7-f8a9-4b0c-1d2e-3f4a5b6c7d8e")


(def ^:private arg-is-fn-field-uuid
  #uuid "c5d6e7f8-a9b0-4c1d-2e3f-4a5b6c7d8e9f")


(def ^:private arg-next-arg-id-field-uuid
  #uuid "d6e7f8a9-b0c1-4d2e-3f4a-5b6c7d8e9f00")


(def ^:private arg-prev-arg-id-field-uuid
  #uuid "e7f8a9b0-c1d2-4e3f-4a5b-6c7d8e9f0011")


(def ^:private arg-description-field-uuid
  #uuid "2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f")


(defn- value-kind-enum-values
  []
  (into [{:uuid (get value-kind-values :null) :value :null}
         {:uuid (get value-kind-values :any) :value :any}
         {:uuid (get value-kind-values :fn) :value :fn}]
        (map (fn [t] {:uuid (get value-kind-values t) :value t})
             ft/supported-types)))


(defn extend-builder
  "Extends a builder with graph schema entities."
  [builder]
  (-> builder
      (ds/add-enum :value-kind value-kind-enum-uuid (value-kind-enum-values))

      ;; ns: namespace entity
      ;; Groups fns by purpose, avoids name collisions.
      ;; parent-id=nil → root namespace
      ;; parent-id set → nested namespace
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

      ;; fn: function entity
      ;; parent-ids=nil/[] → base-fn, has Clojure implementation
      ;; parent-ids=[id] → single inheritance (most common)
      ;; parent-ids=[id1,id2] → multiple inheritance (parents define disjoint args)
      ;; name=nil → local fn (scoped, not globally visible)
      ;; namespace-id → groups fn under a namespace for organization
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
                      :return-type {:uuid fn-return-type-field-uuid
                                    :type :enum
                                    :enum-name :value-kind
                                    :nullable? true}
                      :impl-hash {:uuid fn-impl-hash-field-uuid
                                  :type :text
                                  :nullable? true}
                      :description {:uuid fn-description-field-uuid
                                    :type :text
                                    :nullable? true}})
      (ds/add-constraint :fn {:type :unique :fields [:namespace-id :name]})

      ;; arg: argument entity
      ;; source-id=nil → primary argument (defines interface)
      ;; source-id set → inherited/forwarded argument
      ;; via-fn-id → through which nested fn (for forwarding from inner fns)
      ;; value=nil AND ref-id=nil → exposed (part of fn interface)
      (ds/add-entity :arg arg-entity-uuid
                     {:fn-id {:uuid arg-fn-id-field-uuid
                              :type :ref
                              :ref-entity :fn}
                      :via-fn-id {:uuid arg-via-fn-id-field-uuid
                                  :type :ref
                                  :ref-entity :fn
                                  :nullable? true}
                      :source-id {:uuid arg-source-id-field-uuid
                                  :type :ref
                                  :ref-entity :arg
                                  :nullable? true}
                      :value {:uuid arg-value-field-uuid
                              :type :jsonb
                              :nullable? true}
                      :ref-id {:uuid arg-ref-id-field-uuid
                               :type :ref
                               :ref-entity :fn
                               :nullable? true}
                      :name {:uuid arg-name-field-uuid
                             :type :text
                             :nullable? true}
                      :type {:uuid arg-type-field-uuid
                             :type :enum
                             :enum-name :value-kind
                             :nullable? true}
                      :required {:uuid arg-required-field-uuid
                                 :type :bool
                                 :nullable? true}
                      :is-fn {:uuid arg-is-fn-field-uuid
                              :type :bool
                              :nullable? true}
                      ;; next-arg-id: pointer to the next item in a sequence-arg chain.
                      ;; Used by anchor args (source-id → sequence template, next → first item)
                      ;; and item args (source-id = nil, next → next item or nil at tail).
                      ;; nil for all scalar (non-sequence) args.
                      :next-arg-id {:uuid arg-next-arg-id-field-uuid
                                    :type :ref
                                    :ref-entity :arg
                                    :nullable? true}
                      ;; prev-arg-id: inverse of next-arg-id. For an item arg
                      ;; it points back to the previous item (or to the anchor
                      ;; if this is the head). Maintained by sequence ops so
                      ;; remove/move-up/insert-before are O(1).
                      :prev-arg-id {:uuid arg-prev-arg-id-field-uuid
                                    :type :ref
                                    :ref-entity :arg
                                    :nullable? true}
                      :description {:uuid arg-description-field-uuid
                                    :type :text
                                    :nullable? true}})
      (ds/add-constraint :arg {:type :unique :fields [:fn-id :source-id]})
      (ds/add-constraint :arg {:type :unique :fields [:fn-id :name]})))


(defn build-schema
  "Builds the graph data schema."
  [builder]
  (-> builder
      (extend-builder)
      (ds/build)))
