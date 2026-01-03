(ns graphden.datomic-storage.constraints
  "GraphConstraints implementation for Datomic storage.
   Validates graph integrity constraints using Datomic queries.
   Uses shared validation logic from storage-protocol."
  (:require
    [datomic.client.api :as d]
    [graphden.storage-protocol.interface :as sp]))


;; === ConstraintHelpers implementation for Datomic ===

(defrecord DatomicConstraintHelpers
  [conn-atom]

  sp/ConstraintHelpers

  (get-fn-schema-id-for-fn
    [_this fn-id]
    (when-let [conn @conn-atom]
      (ffirst (d/q '[:find ?schema-id
                     :in $ ?fn-id
                     :where
                     [?e :fn/id ?fn-id]
                     [?e :fn/fn-schema-id ?schema-id]]
                   (d/db conn) fn-id))))


  (get-fn-schema-id-for-arg-schema
    [_this arg-schema-id]
    (when-let [conn @conn-atom]
      (ffirst (d/q '[:find ?schema-id
                     :in $ ?arg-schema-id
                     :where
                     [?e :arg-schema/id ?arg-schema-id]
                     [?e :arg-schema/fn-schema-id ?schema-id]]
                   (d/db conn) arg-schema-id))))


  (get-parent-fn-id
    [_this fn-id]
    (when-let [conn @conn-atom]
      (ffirst (d/q '[:find ?parent-id
                     :in $ ?fn-id
                     :where
                     [?e :fn/id ?fn-id]
                     [?e :fn/parent-fn-id ?parent-id]]
                   (d/db conn) fn-id))))


  (collect-parent-chain
    [this fn-id]
    (sp/collect-parent-chain-impl this fn-id))


  (collect-arg-schema-ids-in-chain
    [this fn-id]
    (let [ancestor-ids (sp/collect-parent-chain this fn-id)]
      (if (empty? ancestor-ids)
        #{}
        (when-let [conn @conn-atom]
          (let [results (d/q '[:find ?arg-schema-id
                               :in $ [?owner-id ...]
                               :where
                               [?e :arg-value/owner-fn-id ?owner-id]
                               [?e :arg-value/arg-schema-id ?arg-schema-id]]
                             (d/db conn) (vec ancestor-ids))]
            (set (map first results)))))))


  (collect-dependency-chain
    [_this owner-fn-id]
    (when-let [conn @conn-atom]
      (let [db (d/db conn)]
        (loop [to-visit [owner-fn-id]
               visited #{}]
          (if (empty? to-visit)
            visited
            (let [current-id (first to-visit)
                  rest-to-visit (rest to-visit)]
              (if (contains? visited current-id)
                (recur rest-to-visit visited)
                (let [;; Get arg-values for current fn
                      arg-values (d/q '[:find ?value
                                        :in $ ?owner-id
                                        :where
                                        [?e :arg-value/owner-fn-id ?owner-id]
                                        [?e :arg-value/value ?value]]
                                      db current-id)
                      ;; Extract UUID candidates from arg-values
                      uuid-candidates (->> arg-values
                                           (map first)
                                           (keep sp/try-parse-uuid)
                                           vec)
                      ;; Batch check: find which UUIDs are actually fn-ids
                      ref-fn-ids (if (empty? uuid-candidates)
                                   []
                                   (let [valid-fn-ids (d/q '[:find ?fn-id
                                                             :in $ [?fn-id ...]
                                                             :where
                                                             [?e :fn/id ?fn-id]]
                                                           db uuid-candidates)]
                                     (map first valid-fn-ids)))]
                  (recur (concat rest-to-visit ref-fn-ids)
                         (conj visited current-id)))))))))))


(defn create-helpers
  "Creates a ConstraintHelpers instance for Datomic."
  [conn-atom]
  (->DatomicConstraintHelpers conn-atom))


;; === Validation functions using shared implementations ===

(defn validate-parent-same-schema!
  "Validates that parent-fn has the same fn-schema-id as fn.
   Throws :constraint-violation/parent-schema-mismatch on violation."
  [conn-atom fn-id parent-fn-id]
  (sp/validate-parent-same-schema-impl (create-helpers conn-atom) fn-id parent-fn-id))


(defn validate-no-arg-override!
  "Validates that arg-schema-id is not already defined in the parent chain.
   Throws :constraint-violation/arg-already-defined on violation."
  [conn-atom fn-id arg-schema-id]
  (sp/validate-no-arg-override-impl (create-helpers conn-atom) fn-id arg-schema-id))


(defn validate-arg-schema-belongs-to-fn!
  "Validates that arg-schema belongs to fn's fn-schema.
   Throws :constraint-violation/arg-schema-mismatch on violation."
  [conn-atom fn-id arg-schema-id]
  (sp/validate-arg-schema-belongs-to-fn-impl (create-helpers conn-atom) fn-id arg-schema-id))


(defn validate-no-inheritance-cycle!
  "Validates that setting parent-fn-id would not create an inheritance cycle.
   Throws :constraint-violation/inheritance-cycle on violation."
  [conn-atom fn-id parent-fn-id]
  (sp/validate-no-inheritance-cycle-impl (create-helpers conn-atom) fn-id parent-fn-id))


(defn validate-no-dependency-cycle!
  "Validates that referencing value-fn-id would not create a dependency cycle.
   Throws :constraint-violation/dependency-cycle on violation."
  [conn-atom owner-fn-id value-fn-id]
  (sp/validate-no-dependency-cycle-impl (create-helpers conn-atom) owner-fn-id value-fn-id))
