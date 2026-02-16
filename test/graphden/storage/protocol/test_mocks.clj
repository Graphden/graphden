(ns graphden.storage.protocol.test-mocks
  "Mock implementations for storage-protocol tests."
  (:require
    [graphden.storage.protocol.interface :as storage]))


;; === Mock ConstraintHelpers for testing shared implementations ===

(defrecord MockConstraintHelpers
  [fn-schema-map arg-schema-fn-schema-map dependency-chain-map]

  storage/ConstraintHelpers

  (get-fn-schema-id-for-fn
    [_this fn-id]
    (get fn-schema-map fn-id))


  (get-fn-schema-id-for-arg-schema
    [_this arg-schema-id]
    (get arg-schema-fn-schema-map arg-schema-id))


  (collect-dependency-chain
    [_this fn-id]
    (get dependency-chain-map fn-id #{fn-id})))
