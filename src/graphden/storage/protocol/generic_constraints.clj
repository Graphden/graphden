(ns graphden.storage.protocol.generic-constraints
  "Generic constraint helpers using StorageCRUD.

   Provides a single ConstraintHelpers implementation that works with any
   storage backend through the StorageCRUD protocol. Backends no longer need
   to implement ConstraintHelpers themselves — they can use these generic
   functions for GraphConstraints validation.

   Usage in backend core.clj:
   ```clojure
   (require '[graphden.storage.protocol.generic-constraints :as gc])

   sp/GraphConstraints
   (validate-no-dependency-cycle! [this owner-fn-id ref-fn-id]
     (gc/validate-no-dependency-cycle! this owner-fn-id ref-fn-id))
   ```"
  (:require
    [graphden.storage.protocol.constraints :as constraints]
    [graphden.storage.protocol.core :as sp]))


;; Operators / kind heads inside a constraint vector. The write-time
;; check (`crud.validation`) and this chain walk MUST agree on "which
;; keywords inside a constraint vector are type-row references", so
;; there is exactly one set — it lives here, the lower layer, and CRUD
;; reads it from here. A new constraint operator added to only one of
;; two copies used to leave the cycle walker blind to half the
;; references it should follow.
(def constraint-op-keywords
  #{:union :variant :fn :refine :map :tuple :and :or :not
    :> :>= :< :<= := :not= :matches :in :exists :every})


(defn constraint-type-ref-names
  "Walk a constraint vector and collect every keyword nested anywhere
   inside it as a bare-name string set, minus the operator heads in
   `constraint-op-keywords`. Finds the type-row references hidden in
   `[:union T1 T2 …]` / `[:variant :tag1 T1 …]` / `[:fn {…} T]` shapes
   — those are stored as keywords, NOT FK columns, so an FK-only walker
   misses them.

   Deliberately over-inclusive: a variant TAG that happens to share a
   name with a type-row is collected too. For the cycle walker that
   only means visiting an unrelated fn (it widens the dependency
   closure, it never claims a cycle that isn't there); for the
   write-time check it can only over-reject, and under-rejection would
   let real cycles through."
  [c]
  (let [walk (fn walk
               [x acc]
               (cond
                 (keyword? x)
                 (if (constraint-op-keywords x)
                   acc
                   (conj acc (name x)))
                 (map? x)
                 (reduce-kv (fn [a _k v] (walk v a)) acc x)
                 (sequential? x)
                 (reduce (fn [a el] (walk el a)) acc x)
                 :else acc))]
    (walk c #{})))


(defrecord GenericConstraintHelpers
  [storage]

  sp/ConstraintHelpers

  (collect-dependency-chain
    [_this owner-fn-id]
    (constraints/collect-dependency-chain-impl
      (fn [current-id]
        (let [fn-rec (sp/read-entity storage :fn current-id)
              bindings (sp/query-entities storage :binding {:fn-id current-id})
              binding-refs (into #{}
                                 ;; ALL edges per binding -- the old
                                 ;; (or ref override) dropped the
                                 ;; type-override edge whenever a
                                 ;; binding carried BOTH (an order-
                                 ;; dependent cycle false-negative);
                                 ;; resolver edges are part of the
                                 ;; closure since the fleet-cell fix.
                                 (comp (mapcat (juxt :ref-fn-id
                                                     :type-override-fn-id
                                                     :resolver-fn-id))
                                       (filter some?))
                                 bindings)
              ;; Single batched query for ALL list-items belonging to
              ;; this fn's bindings (was N queries — one per binding).
              binding-ids (mapv :id bindings)
              item-refs (if (empty? binding-ids)
                          #{}
                          (into #{}
                                (keep :ref-fn-id)
                                (sp/query-entities storage :binding-list-item
                                                   {:binding-id binding-ids})))
              parent-ids (remove nil? (:parent-ids fn-rec))
              type-refs (keep #(get fn-rec %)
                              [:base-fn-id :element-fn-id :return-type-fn-id])
              ;; Constraint-vector type-refs (e.g. `[:union :a :b]`)
              ;; — names resolved to fn-ids via a single batched
              ;; query, slot into the same `all-refs` set so the
              ;; cycle walker doesn't care which slot of the fn
              ;; carried each edge.
              constraint-name-refs (some-> fn-rec :constraint
                                           constraint-type-ref-names)
              constraint-refs (if (empty? constraint-name-refs)
                                #{}
                                (into #{}
                                      (keep :id)
                                      (sp/query-entities storage :fn
                                                         {:name (vec constraint-name-refs)})))
              all-refs (reduce into #{}
                               [binding-refs item-refs parent-ids
                                type-refs constraint-refs])]
          (if (empty? all-refs)
            #{}
            (let [fn-results (sp/read-entities storage :fn (vec all-refs))]
              (set (keys fn-results))))))
      owner-fn-id)))


(defn validate-no-dependency-cycle!
  "Validates that referencing ref-fn does not create dependency cycle.
   Uses StorageCRUD to traverse dependency chain — works with any backend."
  [storage owner-fn-id ref-fn-id]
  (sp/validate-no-dependency-cycle-impl
    (->GenericConstraintHelpers storage) owner-fn-id ref-fn-id))
