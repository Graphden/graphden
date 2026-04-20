(ns hooks.defbase
  (:require [clj-kondo.hooks-api :as api]))

(defn defbase
  "Hook for graphden.executor.defbase/defbase macro.

   Transforms:
   (defbase my-fn
     [a b]
     (+ a b))

   Into something clj-kondo can analyze:
   (def my-fn
     (fn [{:keys [a b]} ctx]
       (let [ctx ctx] (+ a b))))"
  [{:keys [node]}]
  (let [children (rest (:children node))
        ;; First child is the name
        fn-name (first children)
        ;; Skip optional docstring
        rest-children (if (api/string-node? (second children))
                        (drop 2 children)
                        (drop 1 children))
        ;; First after name/docstring is the arg-vector
        args-vec (first rest-children)
        body (rest rest-children)
        arg-syms (when (api/vector-node? args-vec)
                   (:children args-vec))
        ;; Build destructuring map
        keys-vec (api/vector-node (vec (or arg-syms [])))
        destructure-map (api/map-node [(api/keyword-node :keys) keys-vec])
        ;; Build fn form with ctx parameter
        ;; Bind ctx to itself in let so clj-kondo doesn't flag it as unused
        ;; when the body never references it.
        ctx-let (api/list-node
                  (list (api/token-node 'let)
                        (api/vector-node [(api/token-node 'ctx) (api/token-node 'ctx)])
                        (if (= 1 (count body))
                          (first body)
                          (api/list-node (cons (api/token-node 'do) body)))))
        ;; Produce both arities: 1-arg `[args]` (delegates to 2-arg with
        ;; ctx=nil) + 2-arg `[args ctx]` (canonical body). Matches what
        ;; the real macro emits so callsites with either arity pass
        ;; kondo's arity check. The 1-arg version binds `ctx` to nil so
        ;; the shared body form typechecks either way.
        one-arity-let (api/list-node
                        (list (api/token-node 'let)
                              (api/vector-node
                                [(api/token-node 'ctx) (api/token-node 'nil)])
                              (if (= 1 (count body))
                                (first body)
                                (api/list-node (cons (api/token-node 'do) body)))))
        one-arity (api/list-node
                    (list
                      (api/vector-node [destructure-map])
                      one-arity-let))
        two-arity (api/list-node
                    (list
                      (api/vector-node [destructure-map (api/token-node 'ctx)])
                      ctx-let))
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
