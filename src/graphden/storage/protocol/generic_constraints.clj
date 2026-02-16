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
   (validate-arg-schema-belongs-to-fn! [this fn-id arg-schema-id]
     (gc/validate-arg-schema-belongs-to-fn! this fn-id arg-schema-id))
   ```"
  (:require
    [graphden.storage.protocol.constraints :as constraints]
    [graphden.storage.protocol.interface :as sp]))


(defrecord GenericConstraintHelpers
  [storage]

  sp/ConstraintHelpers

  (get-fn-schema-id-for-fn
    [_this fn-id]
    (:fn-schema-id (sp/read-entity storage :fn fn-id)))


  (get-fn-schema-id-for-arg-schema
    [_this arg-schema-id]
    (:fn-schema-id (sp/read-entity storage :arg-schema arg-schema-id)))


  (collect-dependency-chain
    [_this owner-fn-id]
    (constraints/collect-dependency-chain-impl
      (fn [_helpers current-id]
        ;; Get arg-values for current fn via fn-arg join
        (let [fn-args (sp/query-entities storage :fn-arg {:fn-id current-id})
              arg-value-ids (keep :arg-value-id fn-args)]
          (if (empty? arg-value-ids)
            []
            (let [arg-values (sp/read-entities storage :arg-value (vec arg-value-ids))
                  ;; Extract UUID candidates from values
                  uuid-candidates (->> (vals arg-values)
                                       (map :value)
                                       (keep sp/try-parse-uuid)
                                       vec)]
              (if (empty? uuid-candidates)
                []
                ;; Batch check: which UUIDs are actually fns
                (let [fn-results (sp/read-entities storage :fn uuid-candidates)]
                  (set (keys fn-results))))))))
      nil  ; helpers arg (unused by our get-fn-dependencies-fn)
      owner-fn-id)))


(defn validate-arg-schema-belongs-to-fn!
  "Validates that arg-schema belongs to the fn-schema of this fn.
   Uses StorageCRUD to fetch data — works with any backend."
  [storage fn-id arg-schema-id]
  (sp/validate-arg-schema-belongs-to-fn-impl
    (->GenericConstraintHelpers storage) fn-id arg-schema-id))


(defn validate-no-dependency-cycle!
  "Validates that referencing value-fn does not create dependency cycle.
   Uses StorageCRUD to traverse dependency chain — works with any backend."
  [storage owner-fn-id value-fn-id]
  (sp/validate-no-dependency-cycle-impl
    (->GenericConstraintHelpers storage) owner-fn-id value-fn-id))
