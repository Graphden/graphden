(ns graphden.dev.core
  "Development entry point - REPL utilities and system management"
  (:require
    [clojure.java.io :as io]
    [graphden.graph.interface :as graph]
    [integrant.core :as ig]))


(defonce ^:private system (atom nil))


(defn read-config
  "Read and prepare integrant config from file"
  ([]
   (read-config "config.edn"))
  ([path]
   (-> path
       io/resource
       slurp
       ig/read-string)))


(defn start!
  "Start the system"
  ([]
   (start! (read-config)))
  ([config]
   (when @system
     (throw (ex-info "System already running" {})))
   (reset! system (ig/init config))
   :started))


(defn stop!
  "Stop the system"
  []
  (when @system
    (ig/halt! @system)
    (reset! system nil)
    :stopped))


(defn restart!
  "Restart the system"
  []
  (stop!)
  (start!))


(defn get-system
  "Get the current system"
  []
  @system)


(defn get-graph
  "Get graph from system"
  []
  (when-let [sys @system]
    (:graphden.graph.core/graph sys)))


;; Convenience functions for REPL
(defn add-node!
  "Add a node to the graph"
  [node-data]
  (if-let [g (get-graph)]
    (do
      (graph/add-node* g node-data)
      :added)
    (throw (ex-info "System not started" {}))))


(defn get-node
  "Get a node from the graph"
  [node-name]
  (when-let [g (get-graph)]
    (graph/get-node* g node-name)))


(defn delete-node!
  "Delete a node from the graph"
  [node-name]
  (if-let [g (get-graph)]
    (do
      (graph/delete-node* g node-name)
      :deleted)
    (throw (ex-info "System not started" {}))))


(defn all-nodes
  "Get all nodes"
  []
  (when-let [g (get-graph)]
    (graph/get-all-nodes* g)))


(comment
  ;; REPL workflow:
  (start!)

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
  (graph/get-root-ancestor* (get-graph) :print-sum)
  (graph/get-full-args* (get-graph) :print-sum)
  (graph/get-children* (get-graph) :print)

  (stop!))
