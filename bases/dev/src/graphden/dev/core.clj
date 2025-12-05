(ns graphden.dev.core
  "Development entry point - REPL utilities for graph manipulation"
  (:require
    [graphden.graph.interface :as graph]))


;; Graph state - stored in atom for REPL convenience
(defonce ^:private graph-state (atom (graph/create-graph)))


(defn reset-graph!
  "Reset graph to empty state"
  []
  (reset! graph-state (graph/create-graph))
  :reset)


(defn get-graph
  "Get current graph state"
  []
  @graph-state)


;; Convenience functions for REPL (mutate atom)
(defn add-node!
  "Add a node to the graph"
  [node-data]
  (swap! graph-state graph/add-node node-data)
  :added)


(defn delete-node!
  "Delete a node from the graph"
  [node-name]
  (swap! graph-state graph/delete-node node-name)
  :deleted)


(defn rename-node!
  "Rename a node"
  [old-name new-name]
  (swap! graph-state graph/rename-node old-name new-name)
  :renamed)


(defn set-arg!
  "Set arg value in a node"
  [node-name arg-name value]
  (swap! graph-state graph/set-arg-value node-name arg-name value)
  :updated)


;; Query functions (pure, no side effects)
(defn get-node
  "Get a node from the graph"
  [node-name]
  (graph/get-node @graph-state node-name))


(defn all-nodes
  "Get all nodes"
  []
  (graph/get-all-nodes @graph-state))


(defn children
  "Get children of a node"
  [node-name]
  (graph/get-children @graph-state node-name))


(defn root-ancestor
  "Get root ancestor of a node"
  [node-name]
  (graph/get-root-ancestor @graph-state node-name))


(defn full-args
  "Get full args of a node (merged from ancestors)"
  [node-name]
  (graph/get-full-args @graph-state node-name))


(comment
  ;; REPL workflow:
  (reset-graph!)

  ;; Add some nodes
  (add-node! {:node-name :sum
              :args [{:arg-name :a :arg-val nil}
                     {:arg-name :b :arg-val nil}]})

  (add-node! {:node-name :print
              :args [{:arg-name :val :arg-val nil}]})

  (add-node! {:node-name :print-sum
              :parent-name :print
              :args [{:arg-name :val :arg-val :sum}]})

  (get-node :print-sum)
  (all-nodes)

  ;; Get derived data
  (root-ancestor :print-sum)
  (full-args :print-sum)
  (children :print)

  ;; Modify
  (set-arg! :print-sum :val 42)
  (rename-node! :print-sum :my-print)

  ;; Delete
  (delete-node! :my-print)

  ;; Pure function usage (without atom)
  (-> (graph/create-graph)
      (graph/add-node {:node-name :foo :args []})
      (graph/add-node {:node-name :bar :parent-name :foo :args []})))
