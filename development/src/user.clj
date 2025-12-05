(ns user
  "REPL entry point for development.
   Start REPL from project root with: clj -A:dev"
  (:require
    [graphden.dev.core :as dev]
    [graphden.graph.interface :as graph]))


;; Re-export dev functions for convenience
(def start! dev/start!)
(def stop! dev/stop!)
(def restart! dev/restart!)
(def get-graph dev/get-graph)
(def add-node! dev/add-node!)
(def get-node dev/get-node)
(def delete-node! dev/delete-node!)
(def all-nodes dev/all-nodes)


(comment
  ;; Quick start:
  (start!)

  ;; Create base functions
  (add-node! {:node-name :sum
              :args [{:arg-name :a :arg-val nil}
                     {:arg-name :b :arg-val nil}]})

  (add-node! {:node-name :multiply
              :args [{:arg-name :x :arg-val nil}
                     {:arg-name :y :arg-val nil}]})

  (add-node! {:node-name :print
              :args [{:arg-name :val :arg-val nil}]})

  ;; Create compositions
  (add-node! {:node-name :print-sum
              :parent-name :print
              :args [{:arg-name :val :arg-val :sum}]})

  (add-node! {:node-name :print-product
              :parent-name :print
              :args [{:arg-name :val :arg-val :multiply}]})

  ;; Query
  (all-nodes)
  (get-node :print-sum)

  ;; Derived queries
  (graph/get-root-ancestor* (get-graph) :print-sum)  ;; => :print
  (graph/get-full-args* (get-graph) :print-sum)      ;; merged args
  (graph/get-children* (get-graph) :print)           ;; => #{:print-sum :print-product}

  (stop!))
