(ns graphden.storage-protocol.test-mocks
  "Mock implementations for storage-protocol tests."
  (:require
    [graphden.storage-protocol.interface :as storage]))


;; === Mock ConstraintHelpers for testing shared implementations ===

(defrecord MockConstraintHelpers
  [fn-schema-map arg-schema-fn-schema-map parent-map arg-schema-ids-in-chain-map dependency-chain-map]

  storage/ConstraintHelpers

  (get-fn-schema-id-for-fn
    [_this fn-id]
    (get fn-schema-map fn-id))


  (get-fn-schema-id-for-arg-schema
    [_this arg-schema-id]
    (get arg-schema-fn-schema-map arg-schema-id))


  (get-parent-fn-id
    [_this fn-id]
    (get parent-map fn-id))


  (collect-parent-chain
    [this fn-id]
    (storage/collect-parent-chain-impl this fn-id))


  (collect-arg-schema-ids-in-chain
    [_this fn-id]
    (get arg-schema-ids-in-chain-map fn-id #{}))


  (collect-dependency-chain
    [_this fn-id]
    (get dependency-chain-map fn-id #{fn-id})))
