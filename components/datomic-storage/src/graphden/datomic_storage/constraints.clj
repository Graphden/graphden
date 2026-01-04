(ns graphden.datomic-storage.constraints
  "GraphConstraints implementation for Datomic storage.
   Validates graph integrity constraints using Datomic queries.
   Uses shared validation logic from storage-protocol."
  (:require
    [clojure.tools.logging :as log]
    [datomic.client.api :as d]
    [graphden.storage-protocol.interface :as sp]))


;; === Helper for connection validation ===

(defn- get-conn!
  "Gets connection from atom, throws if nil."
  [conn-atom]
  (or @conn-atom
      (throw (ex-info "Storage not initialized"
                      {:type :storage-not-initialized}))))


;; === ConstraintHelpers implementation for Datomic ===

(defrecord DatomicConstraintHelpers
  [conn-atom]

  sp/ConstraintHelpers

  (get-fn-schema-id-for-fn
    [_this fn-id]
    (let [conn (get-conn! conn-atom)]
      (ffirst (d/q '[:find ?schema-id
                     :in $ ?fn-id
                     :where
                     [?e :fn/id ?fn-id]
                     [?e :fn/fn-schema-id ?schema-id]]
                   (d/db conn) fn-id))))


  (get-fn-schema-id-for-arg-schema
    [_this arg-schema-id]
    (let [conn (get-conn! conn-atom)]
      (ffirst (d/q '[:find ?schema-id
                     :in $ ?arg-schema-id
                     :where
                     [?e :arg-schema/id ?arg-schema-id]
                     [?e :arg-schema/fn-schema-id ?schema-id]]
                   (d/db conn) arg-schema-id))))


  (get-parent-fn-id
    [_this fn-id]
    (let [conn (get-conn! conn-atom)]
      (ffirst (d/q '[:find ?parent-id
                     :in $ ?fn-id
                     :where
                     [?e :fn/id ?fn-id]
                     [?e :fn/parent-fn-id ?parent-id]]
                   (d/db conn) fn-id))))


  (collect-parent-chain
    [this fn-id]
    ;; First check if there's a parent at all (using get-parent-fn-id for coverage)
    (let [parent-id (sp/get-parent-fn-id this fn-id)]
      (if-not parent-id
        #{}
        ;; Optimized: fetch all fn parent relationships in one query, then traverse in memory.
        ;; This reduces O(N) database queries to O(1) for deep inheritance chains.
        (let [conn (get-conn! conn-atom)
              db (d/db conn)
              ;; Fetch all fn-id -> parent-fn-id mappings
              all-parents (d/q '[:find ?fn-id ?parent-id
                                 :where
                                 [?e :fn/id ?fn-id]
                                 [?e :fn/parent-fn-id ?parent-id]]
                               db)
              parent-map (into {} all-parents)]
          ;; Traverse parent chain in memory
          (loop [current-id (get parent-map fn-id)
                 ancestor-ids #{}]
            (if (or (nil? current-id) (contains? ancestor-ids current-id))
              ancestor-ids
              (recur (get parent-map current-id)
                     (conj ancestor-ids current-id))))))))


  (collect-arg-schema-ids-in-chain
    [this fn-id]
    (let [ancestor-ids (sp/collect-parent-chain this fn-id)]
      (if (empty? ancestor-ids)
        #{}
        (let [conn (get-conn! conn-atom)
              results (d/q '[:find ?arg-schema-id
                             :in $ [?owner-id ...]
                             :where
                             [?e :arg-value/owner-fn-id ?owner-id]
                             [?e :arg-value/arg-schema-id ?arg-schema-id]]
                           (d/db conn) (vec ancestor-ids))]
          (set (map first results))))))


  (collect-dependency-chain
    [_this owner-fn-id]
    (let [conn (get-conn! conn-atom)
          db (d/db conn)]
      (loop [to-visit [owner-fn-id]
             visited #{}
             iter-count 0]
        ;; Check iteration limit to prevent infinite loops
        (sp/check-graph-iteration-limit! iter-count owner-fn-id)
        (if (empty? to-visit)
          visited
          (let [current-id (first to-visit)
                rest-to-visit (rest to-visit)]
            (if (contains? visited current-id)
              (recur rest-to-visit visited (inc iter-count))
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
                       (conj visited current-id)
                       (inc iter-count))))))))))


(defn create-helpers
  "Creates a ConstraintHelpers instance for Datomic."
  [conn-atom]
  (->DatomicConstraintHelpers conn-atom))


;; === Connection validation ===

(defn- ensure-connection!
  "Throws if connection is nil. Returns connection if valid."
  [conn-atom operation-name]
  (when-not @conn-atom
    (log/error "Constraint validation failed: storage not initialized" {:operation operation-name})
    (throw (ex-info "Cannot perform validation: storage not initialized"
                    {:type :storage-not-initialized
                     :operation operation-name}))))


;; === Validation functions using shared implementations ===

(defn validate-parent-same-schema!
  "Validates that parent-fn has the same fn-schema-id as fn.
   Throws :constraint-violation/parent-schema-mismatch on violation.
   Throws :storage-not-initialized if storage is closed."
  [conn-atom fn-id parent-fn-id]
  (ensure-connection! conn-atom :validate-parent-same-schema)
  (sp/validate-parent-same-schema-impl (create-helpers conn-atom) fn-id parent-fn-id))


(defn validate-no-arg-override!
  "Validates that arg-schema-id is not already defined in the parent chain.
   Throws :constraint-violation/arg-already-defined on violation.
   Throws :storage-not-initialized if storage is closed."
  [conn-atom fn-id arg-schema-id]
  (ensure-connection! conn-atom :validate-no-arg-override)
  (sp/validate-no-arg-override-impl (create-helpers conn-atom) fn-id arg-schema-id))


(defn validate-arg-schema-belongs-to-fn!
  "Validates that arg-schema belongs to fn's fn-schema.
   Throws :constraint-violation/arg-schema-mismatch on violation.
   Throws :storage-not-initialized if storage is closed."
  [conn-atom fn-id arg-schema-id]
  (ensure-connection! conn-atom :validate-arg-schema-belongs-to-fn)
  (sp/validate-arg-schema-belongs-to-fn-impl (create-helpers conn-atom) fn-id arg-schema-id))


(defn validate-no-inheritance-cycle!
  "Validates that setting parent-fn-id would not create an inheritance cycle.
   Throws :constraint-violation/inheritance-cycle on violation.
   Throws :storage-not-initialized if storage is closed."
  [conn-atom fn-id parent-fn-id]
  (ensure-connection! conn-atom :validate-no-inheritance-cycle)
  (sp/validate-no-inheritance-cycle-impl (create-helpers conn-atom) fn-id parent-fn-id))


(defn validate-no-dependency-cycle!
  "Validates that referencing value-fn-id would not create a dependency cycle.
   Throws :constraint-violation/dependency-cycle on violation.
   Throws :storage-not-initialized if storage is closed."
  [conn-atom owner-fn-id value-fn-id]
  (ensure-connection! conn-atom :validate-no-dependency-cycle)
  (sp/validate-no-dependency-cycle-impl (create-helpers conn-atom) owner-fn-id value-fn-id))
