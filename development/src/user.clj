(ns user
  "REPL entry point for development.
   Start REPL from project root with: clj -A:dev"
  (:require
    [graphden.dev.core :as dev]
    [graphden.graph.interface :as graph]))


;; Re-export dev functions for convenience
(def reset-graph! dev/reset-graph!)
(def get-graph dev/get-graph)
(def add-node! dev/add-node!)
(def delete-node! dev/delete-node!)
(def rename-node! dev/rename-node!)
(def set-arg! dev/set-arg!)
(def get-node dev/get-node)
(def all-nodes dev/all-nodes)
(def children dev/children)
(def root-ancestor dev/root-ancestor)
(def full-args dev/full-args)


(comment
  ;; Quick start:
  (reset-graph!)

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
  (root-ancestor :print-sum)   ;; => :print
  (full-args :print-sum)       ;; merged args
  (children :print)            ;; => #{:print-sum :print-product}

  ;; Pure functional usage
  (-> (graph/create-graph)
      (graph/add-node {:node-name :foo :args []})
      (graph/add-node {:node-name :bar :parent-name :foo :args []})))
