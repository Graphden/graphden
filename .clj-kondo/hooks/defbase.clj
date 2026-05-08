(ns hooks.defbase
  (:require [clj-kondo.hooks-api :as api]))

(defn- arg-tokens
  [arg-syms]
  (mapv #(api/token-node (symbol (str (:value %)))) (or arg-syms [])))

(defn- wrap-body-using-syms
  "Wrap `body-form` in a `(do <syms…> body)` so kondo treats every arg
   sym (and `ctx`) as referenced. The macro substitutes arg symbols
   pre-shadow, so a body that only shadows them is the designed-for
   path — not an unused-binding."
  [arg-syms body-form ctx-sym]
  (api/list-node
    (concat [(api/token-node 'do)]
            (arg-tokens arg-syms)
            [ctx-sym body-form])))

(defn defbase
  "Hook for graphden.executor.defbase/defbase macro.

   Transforms:
   (defbase my-fn
     [a b]
     (+ a b))

   Into something clj-kondo can analyze:
   (def my-fn
     (fn ([{:keys [a b]}]      (do a b nil (+ a b)))
         ([{:keys [a b]} ctx]  (do a b ctx (+ a b)))))"
  [{:keys [node]}]
  (let [children (rest (:children node))
        fn-name (first children)
        rest-children (if (api/string-node? (second children))
                        (drop 2 children)
                        (drop 1 children))
        args-vec (first rest-children)
        body (rest rest-children)
        arg-syms (when (api/vector-node? args-vec)
                   (:children args-vec))
        keys-vec (api/vector-node (vec (or arg-syms [])))
        destructure-map (api/map-node [(api/keyword-node :keys) keys-vec])
        body-form (if (= 1 (count body))
                    (first body)
                    (api/list-node (cons (api/token-node 'do) body)))
        two-arity-body (wrap-body-using-syms arg-syms body-form
                                             (api/token-node 'ctx))
        ;; 1-arity has no `ctx` param; bind it to nil so a body that
        ;; references `ctx` still resolves.
        one-arity-body (api/list-node
                         (list (api/token-node 'let)
                               (api/vector-node
                                 [(api/token-node 'ctx) (api/token-node 'nil)])
                               (wrap-body-using-syms arg-syms body-form
                                                     (api/token-node 'ctx))))
        one-arity (api/list-node
                    (list
                      (api/vector-node [destructure-map])
                      one-arity-body))
        two-arity (api/list-node
                    (list
                      (api/vector-node [destructure-map (api/token-node 'ctx)])
                      two-arity-body))
        fn-form (api/list-node
                  (list
                    (api/token-node 'fn)
                    one-arity
                    two-arity))
        new-node (api/list-node
                   (list
                     (api/token-node 'def)
                     fn-name
                     fn-form))]
    {:node new-node}))
