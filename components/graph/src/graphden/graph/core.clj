(ns graphden.graph.core
  "Graph helpers - optional schema validation wrapper"
  (:require
    [graphden.graph.interface :as graph]
    [graphden.schema.interface :as schema]))


(defn validate-node!
  "Validate node data against schema, throw if invalid"
  [schema-provider node-data]
  (let [result (schema/validate* schema-provider :node node-data)]
    (when-not (:valid? result)
      (throw (ex-info "Invalid node data"
                      {:errors (:errors result)
                       :node-data node-data})))))


(defn add-node-validated
  "Add node with schema validation"
  [graph schema-provider node-data]
  (validate-node! schema-provider node-data)
  (graph/add-node graph node-data))
