(ns graphden.cache-data-schema.interface
  "Cache data schema definition.

   Extends graph-data-schema with entities for caching execution graphs:
   - cached-fn: denormalized fn data for fast graph loading
   - cached-fn-schema: denormalized fn-schema data
   - cached-arg-schema: denormalized arg-schema data
   - cached-merged-arg: precomputed merged argument values
   - cache-fn-dep: tracks fn dependencies with ref-count
   - cache-fn-schema-dep: tracks fn-schema dependencies with ref-count
   - cache-arg-schema-dep: tracks arg-schema dependencies with ref-count

   These entities enable O(1) execution graph loading instead of O(depth)."
  (:require
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.graph-data-schema.interface :as gds]))


;; === Stable UUIDs for cache schema elements ===
;;
;; See graph-data-schema for explanation of UUID stability.
;; IMPORTANT: Never change these UUIDs!

;; Entity UUIDs
(def ^:private cached-fn-entity-uuid
  #uuid "f1a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c")


(def ^:private cached-fn-schema-entity-uuid
  #uuid "a2b3c4d5-e6f7-4a8b-9c0d-1e2f3a4b5c6d")


(def ^:private cached-arg-schema-entity-uuid
  #uuid "b3c4d5e6-f7a8-4b9c-0d1e-2f3a4b5c6d7e")


(def ^:private cached-merged-arg-entity-uuid
  #uuid "c4d5e6f7-a8b9-4c0d-1e2f-3a4b5c6d7e8f")


(def ^:private cache-fn-dep-entity-uuid
  #uuid "d5e6f7a8-b9c0-4d1e-2f3a-4b5c6d7e8f9a")


(def ^:private cache-fn-schema-dep-entity-uuid
  #uuid "e6f7a8b9-c0d1-4e2f-3a4b-5c6d7e8f9a0b")


(def ^:private cache-arg-schema-dep-entity-uuid
  #uuid "f7a8b9c0-d1e2-4f3a-4b5c-6d7e8f9a0b1c")


(def ^:private cache-call-site-dep-entity-uuid
  #uuid "a8b9c0d1-e2f3-4a4b-5c6d-7e8f9a0b1c2d")


;; Field UUIDs for :cached-fn
(def ^:private cached-fn-cache-id-field-uuid
  #uuid "01a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c")


(def ^:private cached-fn-fn-id-field-uuid
  #uuid "02b3c4d5-e6f7-4a8b-9c0d-1e2f3a4b5c6d")


(def ^:private cached-fn-name-field-uuid
  #uuid "03c4d5e6-f7a8-4b9c-0d1e-2f3a4b5c6d7e")


(def ^:private cached-fn-fn-schema-id-field-uuid
  #uuid "04d5e6f7-a8b9-4c0d-1e2f-3a4b5c6d7e8f")


;; Field UUIDs for :cached-fn-schema
(def ^:private cached-fn-schema-cache-id-field-uuid
  #uuid "11a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c")


(def ^:private cached-fn-schema-fn-schema-id-field-uuid
  #uuid "12b3c4d5-e6f7-4a8b-9c0d-1e2f3a4b5c6d")


(def ^:private cached-fn-schema-name-field-uuid
  #uuid "13c4d5e6-f7a8-4b9c-0d1e-2f3a4b5c6d7e")


(def ^:private cached-fn-schema-base-fn-name-field-uuid
  #uuid "14d5e6f7-a8b9-4c0d-1e2f-3a4b5c6d7e8f")


(def ^:private cached-fn-schema-returned-type-field-uuid
  #uuid "15e6f7a8-b9c0-4d1e-2f3a-4b5c6d7e8f9a")


;; Field UUIDs for :cached-arg-schema
(def ^:private cached-arg-schema-cache-id-field-uuid
  #uuid "21a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c")


(def ^:private cached-arg-schema-arg-schema-id-field-uuid
  #uuid "22b3c4d5-e6f7-4a8b-9c0d-1e2f3a4b5c6d")


(def ^:private cached-arg-schema-fn-schema-id-field-uuid
  #uuid "23c4d5e6-f7a8-4b9c-0d1e-2f3a4b5c6d7e")


(def ^:private cached-arg-schema-name-field-uuid
  #uuid "24d5e6f7-a8b9-4c0d-1e2f-3a4b5c6d7e8f")


(def ^:private cached-arg-schema-type-field-uuid
  #uuid "25e6f7a8-b9c0-4d1e-2f3a-4b5c6d7e8f9a")


(def ^:private cached-arg-schema-required-field-uuid
  #uuid "26f7a8b9-c0d1-4e2f-3a4b-5c6d7e8f9a0b")


;; Field UUIDs for :cached-merged-arg
(def ^:private cached-merged-arg-cache-id-field-uuid
  #uuid "31a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c")


(def ^:private cached-merged-arg-fn-id-field-uuid
  #uuid "32b3c4d5-e6f7-4a8b-9c0d-1e2f3a4b5c6d")


(def ^:private cached-merged-arg-arg-schema-id-field-uuid
  #uuid "33c4d5e6-f7a8-4b9c-0d1e-2f3a4b5c6d7e")


(def ^:private cached-merged-arg-value-field-uuid
  #uuid "34d5e6f7-a8b9-4c0d-1e2f-3a4b5c6d7e8f")


;; Field UUIDs for :cache-fn-dep
(def ^:private cache-fn-dep-cache-id-field-uuid
  #uuid "41a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c")


(def ^:private cache-fn-dep-fn-id-field-uuid
  #uuid "42b3c4d5-e6f7-4a8b-9c0d-1e2f3a4b5c6d")


(def ^:private cache-fn-dep-ref-count-field-uuid
  #uuid "43c4d5e6-f7a8-4b9c-0d1e-2f3a4b5c6d7e")


;; Field UUIDs for :cache-fn-schema-dep
(def ^:private cache-fn-schema-dep-cache-id-field-uuid
  #uuid "51a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c")


(def ^:private cache-fn-schema-dep-fn-schema-id-field-uuid
  #uuid "52b3c4d5-e6f7-4a8b-9c0d-1e2f3a4b5c6d")


(def ^:private cache-fn-schema-dep-ref-count-field-uuid
  #uuid "53c4d5e6-f7a8-4b9c-0d1e-2f3a4b5c6d7e")


;; Field UUIDs for :cache-arg-schema-dep
(def ^:private cache-arg-schema-dep-cache-id-field-uuid
  #uuid "61a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c")


(def ^:private cache-arg-schema-dep-arg-schema-id-field-uuid
  #uuid "62b3c4d5-e6f7-4a8b-9c0d-1e2f3a4b5c6d")


(def ^:private cache-arg-schema-dep-ref-count-field-uuid
  #uuid "63c4d5e6-f7a8-4b9c-0d1e-2f3a4b5c6d7e")


;; Field UUIDs for :cache-call-site-dep
(def ^:private cache-call-site-dep-cache-id-field-uuid
  #uuid "71a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c")


(def ^:private cache-call-site-dep-call-site-id-field-uuid
  #uuid "72b3c4d5-e6f7-4a8b-9c0d-1e2f3a4b5c6d")


(def ^:private cache-call-site-dep-ref-count-field-uuid
  #uuid "73c4d5e6-f7a8-4b9c-0d1e-2f3a4b5c6d7e")


(defn extend-builder
  "Extends a builder (that already has graph-data-schema entities) with cache entities.
   Returns the builder for further extension or finalization.

   Expects :value-kind enum and :fn, :fn-schema, :arg-schema entities to be defined."
  [builder]
  (-> builder
      ;; cached-fn: denormalized copy of fn data within a cache
      (ds/add-entity :cached-fn cached-fn-entity-uuid
                     {:cache-id {:uuid cached-fn-cache-id-field-uuid
                                 :type :ref :ref-entity :fn}
                      :fn-id {:uuid cached-fn-fn-id-field-uuid
                              :type :uuid}
                      :name {:uuid cached-fn-name-field-uuid
                             :type :text}
                      :fn-schema-id {:uuid cached-fn-fn-schema-id-field-uuid
                                     :type :uuid}})
      (ds/add-constraint :cached-fn {:type :unique :fields [:cache-id :fn-id]})

      ;; cached-fn-schema: denormalized copy of fn-schema data
      (ds/add-entity :cached-fn-schema cached-fn-schema-entity-uuid
                     {:cache-id {:uuid cached-fn-schema-cache-id-field-uuid
                                 :type :ref :ref-entity :fn}
                      :fn-schema-id {:uuid cached-fn-schema-fn-schema-id-field-uuid
                                     :type :uuid}
                      :name {:uuid cached-fn-schema-name-field-uuid
                             :type :text}
                      :base-fn-name {:uuid cached-fn-schema-base-fn-name-field-uuid
                                     :type :text
                                     :nullable? true}
                      :returned-type {:uuid cached-fn-schema-returned-type-field-uuid
                                      :type :enum :enum-name :value-kind}})
      (ds/add-constraint :cached-fn-schema {:type :unique :fields [:cache-id :fn-schema-id]})

      ;; cached-arg-schema: denormalized copy of arg-schema data
      (ds/add-entity :cached-arg-schema cached-arg-schema-entity-uuid
                     {:cache-id {:uuid cached-arg-schema-cache-id-field-uuid
                                 :type :ref :ref-entity :fn}
                      :arg-schema-id {:uuid cached-arg-schema-arg-schema-id-field-uuid
                                      :type :uuid}
                      :fn-schema-id {:uuid cached-arg-schema-fn-schema-id-field-uuid
                                     :type :uuid}
                      :name {:uuid cached-arg-schema-name-field-uuid
                             :type :text}
                      :type {:uuid cached-arg-schema-type-field-uuid
                             :type :enum :enum-name :value-kind}
                      :required {:uuid cached-arg-schema-required-field-uuid
                                 :type :bool}})
      (ds/add-constraint :cached-arg-schema {:type :unique :fields [:cache-id :arg-schema-id]})

      ;; cached-merged-arg: precomputed merged argument values for each fn in the graph
      (ds/add-entity :cached-merged-arg cached-merged-arg-entity-uuid
                     {:cache-id {:uuid cached-merged-arg-cache-id-field-uuid
                                 :type :ref :ref-entity :fn}
                      :fn-id {:uuid cached-merged-arg-fn-id-field-uuid
                              :type :uuid}
                      :arg-schema-id {:uuid cached-merged-arg-arg-schema-id-field-uuid
                                      :type :uuid}
                      :value {:uuid cached-merged-arg-value-field-uuid
                              :type :union :variants (gds/value-variants)}})
      (ds/add-constraint :cached-merged-arg {:type :unique :fields [:cache-id :fn-id :arg-schema-id]})

      ;; cache-fn-dep: tracks which fns are used in each cache (with ref-count)
      (ds/add-entity :cache-fn-dep cache-fn-dep-entity-uuid
                     {:cache-id {:uuid cache-fn-dep-cache-id-field-uuid
                                 :type :ref :ref-entity :fn}
                      :dep-fn-id {:uuid cache-fn-dep-fn-id-field-uuid
                                  :type :ref :ref-entity :fn}
                      :ref-count {:uuid cache-fn-dep-ref-count-field-uuid
                                  :type :int}})
      (ds/add-constraint :cache-fn-dep {:type :unique :fields [:cache-id :dep-fn-id]})

      ;; cache-fn-schema-dep: tracks which fn-schemas are used in each cache
      (ds/add-entity :cache-fn-schema-dep cache-fn-schema-dep-entity-uuid
                     {:cache-id {:uuid cache-fn-schema-dep-cache-id-field-uuid
                                 :type :ref :ref-entity :fn}
                      :dep-fn-schema-id {:uuid cache-fn-schema-dep-fn-schema-id-field-uuid
                                         :type :ref :ref-entity :fn-schema}
                      :ref-count {:uuid cache-fn-schema-dep-ref-count-field-uuid
                                  :type :int}})
      (ds/add-constraint :cache-fn-schema-dep {:type :unique :fields [:cache-id :dep-fn-schema-id]})

      ;; cache-arg-schema-dep: tracks which arg-schemas are used in each cache
      (ds/add-entity :cache-arg-schema-dep cache-arg-schema-dep-entity-uuid
                     {:cache-id {:uuid cache-arg-schema-dep-cache-id-field-uuid
                                 :type :ref :ref-entity :fn}
                      :dep-arg-schema-id {:uuid cache-arg-schema-dep-arg-schema-id-field-uuid
                                          :type :ref :ref-entity :arg-schema}
                      :ref-count {:uuid cache-arg-schema-dep-ref-count-field-uuid
                                  :type :int}})
      (ds/add-constraint :cache-arg-schema-dep {:type :unique :fields [:cache-id :dep-arg-schema-id]})

      ;; cache-call-site-dep: tracks which call-sites are used in each cache
      (ds/add-entity :cache-call-site-dep cache-call-site-dep-entity-uuid
                     {:cache-id {:uuid cache-call-site-dep-cache-id-field-uuid
                                 :type :ref :ref-entity :fn}
                      :dep-call-site-id {:uuid cache-call-site-dep-call-site-id-field-uuid
                                         :type :ref :ref-entity :call-site}
                      :ref-count {:uuid cache-call-site-dep-ref-count-field-uuid
                                  :type :int}})
      (ds/add-constraint :cache-call-site-dep {:type :unique :fields [:cache-id :dep-call-site-id]})))


(defn build-schema
  "Builds the complete schema with both graph and cache entities.
   Returns a built DataSchema instance."
  [builder]
  (-> builder
      (gds/extend-builder)
      (extend-builder)
      (ds/build)))


;; List of cache-only entities for storage implementations
(def cache-entities
  "Set of entity names that are cache-specific (not part of base graph schema)."
  #{:cached-fn
    :cached-fn-schema
    :cached-arg-schema
    :cached-merged-arg
    :cache-fn-dep
    :cache-fn-schema-dep
    :cache-arg-schema-dep
    :cache-call-site-dep})
