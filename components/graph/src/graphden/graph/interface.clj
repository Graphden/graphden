(ns graphden.graph.interface
  "Graph protocol - public API for working with function composition graph")


(defprotocol Graph
  "Protocol for graph operations"

  ;; Node CRUD
  (add-node
    [this node-data]
    "Add a node. node-data: {:node-name :foo :parent-name :bar :args [...]}")

  (delete-node
    [this node-name]
    "Delete a node (must have no children or arg refs)")

  (rename-node
    [this old-name new-name]
    "Rename a node")

  (get-node
    [this node-name]
    "Get node by name, returns nil if not found")

  (get-all-nodes
    [this]
    "Get all nodes")

  ;; Arg operations
  (set-arg-value
    [this node-name arg-name value]
    "Set arg value (only for child nodes)")

  ;; Derived queries (may use cache)
  (get-root-ancestor
    [this node-name]
    "Get the root ancestor of a node (top of inheritance chain)")

  (get-full-args
    [this node-name]
    "Get full args including inherited ones")

  (get-children
    [this node-name]
    "Get direct children of a node")

  (get-arg-refs
    [this node-name]
    "Get nodes that reference this node as arg value"))


;; Wrapper functions
(defn add-node*
  "Add a node to graph"
  [graph node-data]
  (add-node graph node-data))


(defn delete-node*
  "Delete a node from graph"
  [graph node-name]
  (delete-node graph node-name))


(defn rename-node*
  "Rename a node in graph"
  [graph old-name new-name]
  (rename-node graph old-name new-name))


(defn get-node*
  "Get node by name"
  [graph node-name]
  (get-node graph node-name))


(defn get-all-nodes*
  "Get all nodes"
  [graph]
  (get-all-nodes graph))


(defn set-arg-value*
  "Set arg value"
  [graph node-name arg-name value]
  (set-arg-value graph node-name arg-name value))


(defn get-root-ancestor*
  "Get root ancestor"
  [graph node-name]
  (get-root-ancestor graph node-name))


(defn get-full-args*
  "Get full args"
  [graph node-name]
  (get-full-args graph node-name))


(defn get-children*
  "Get children"
  [graph node-name]
  (get-children graph node-name))


(defn get-arg-refs*
  "Get arg refs"
  [graph node-name]
  (get-arg-refs graph node-name))
