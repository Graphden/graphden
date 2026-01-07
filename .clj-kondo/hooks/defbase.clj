(ns hooks.defbase
  (:require [clj-kondo.hooks-api :as api]))

(defn defbase
  "Hook for graphden.fn-registry.macros/defbase macro.

   Transforms:
   (defbase my-fn
     {:args {:a :int, :b :int}
      :return-type :int}
     (+ a b))

   Into something clj-kondo can analyze:
   (def my-fn
     (fn [{:keys [a b]} ctx]
       (+ a b)))"
  [{:keys [node]}]
  (let [children (rest (:children node))
        ;; First child is the name
        fn-name (first children)
        ;; Skip optional docstring
        rest-children (if (api/string-node? (second children))
                        (drop 2 children)
                        (drop 1 children))
        ;; First after name/docstring is opts map
        opts-node (first rest-children)
        ;; Rest is body
        body (rest rest-children)
        ;; Extract arg names from opts map
        opts-children (:children opts-node)
        args-idx (some (fn [[i n]]
                         (when (and (api/keyword-node? n)
                                    (= :args (api/sexpr n)))
                           i))
                       (map-indexed vector opts-children))
        args-map (when args-idx (nth opts-children (inc args-idx)))
        ;; Get arg symbols
        arg-syms (when args-map
                   (->> (:children args-map)
                        (partition 2)
                        (map first)
                        (map (fn [n]
                               (if (api/keyword-node? n)
                                 (api/token-node (symbol (name (api/sexpr n))))
                                 n)))))
        ;; Build destructuring map
        keys-vec (api/vector-node (vec arg-syms))
        destructure-map (api/map-node [(api/keyword-node :keys) keys-vec])
        ;; Build fn form with ctx parameter
        ;; We also bind ctx to itself in let to avoid "unused" warnings when ctx is used
        ;; For clj-kondo, ctx is always "used" because it's referenced in the let
        ctx-let (api/list-node
                  (list (api/token-node 'let)
                        (api/vector-node [(api/token-node 'ctx) (api/token-node 'ctx)])
                        ;; Wrap body in do if multiple forms
                        (if (= 1 (count body))
                          (first body)
                          (api/list-node (cons (api/token-node 'do) body)))))
        fn-form (api/list-node
                  (list
                    (api/token-node 'fn)
                    (api/vector-node [destructure-map (api/token-node 'ctx)])
                    ctx-let))
        ;; Build def form
        new-node (api/list-node
                   (list
                     (api/token-node 'def)
                     fn-name
                     fn-form))]
    {:node new-node}))
