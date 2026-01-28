(ns graphden.datomic-storage.constraints
  "GraphConstraints implementation for Datomic storage.
   Validates graph integrity constraints using Datomic queries.
   Uses shared validation logic from storage-protocol."
  (:require
    [datomic.client.api :as d]
    [graphden.storage-protocol.interface :as sp]))


;; === Helper for connection validation ===

(defn- get-conn!
  "Gets connection from atom, throws if nil.
   Includes operation context for better debugging."
  ([conn-atom]
   (get-conn! conn-atom :constraint-check))
  ([conn-atom operation]
   (or @conn-atom
       (throw (ex-info "Storage not initialized"
                       {:type :storage-not-initialized
                        :operation operation
                        :hint "Call initialize before using storage operations"})))))


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


  (collect-dependency-chain
    [_this owner-fn-id]
    (let [conn (get-conn! conn-atom)
          db (d/db conn)]
      ;; Use generic BFS traversal with database queries in get-neighbors-fn
      (sp/traverse-bfs
        owner-fn-id
        (fn [current-id]
          ;; Get arg-values for current fn via fn-arg join
          ;; fn-arg binds fn-id to arg-value-id
          (let [arg-values (d/q '[:find ?value
                                  :in $ ?fn-id
                                  :where
                                  [?fa :fn-arg/fn-id ?fn-id]
                                  [?fa :fn-arg/arg-value-id ?av-id]
                                  [?av :arg-value/id ?av-id]
                                  [?av :arg-value/value ?value]]
                                db current-id)
                ;; Extract UUID candidates from arg-values
                uuid-candidates (->> arg-values
                                     (map first)
                                     (keep sp/try-parse-uuid)
                                     vec)]
            ;; Batch check: find which UUIDs are actually fn-ids
            (if (empty? uuid-candidates)
              []
              (let [valid-fn-ids (d/q '[:find ?fn-id
                                        :in $ [?fn-id ...]
                                        :where
                                        [?e :fn/id ?fn-id]]
                                      db uuid-candidates)]
                (map first valid-fn-ids)))))
        {:context-id owner-fn-id}))))


(defn create-helpers
  "Creates a ConstraintHelpers instance for Datomic."
  [conn-atom]
  (->DatomicConstraintHelpers conn-atom))


;; === Validation functions using shared implementations ===

(defn validate-arg-schema-belongs-to-fn!
  [conn-atom fn-id arg-schema-id]
  (sp/validate-arg-schema-belongs-to-fn-impl (create-helpers conn-atom) fn-id arg-schema-id))


(defn validate-no-dependency-cycle!
  [conn-atom owner-fn-id value-fn-id]
  (sp/validate-no-dependency-cycle-impl (create-helpers conn-atom) owner-fn-id value-fn-id))
