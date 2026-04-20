(ns hooks.fn-impl
  (:require [clj-kondo.hooks-api :as api]))

(defn fn-impl
  "Hook for `graphden.executor.test-setup/fn-impl`.

   Transforms:
     (setup/fn-impl [a b] body)

   Into something clj-kondo can analyze — avoids referring to
   `graphden.executor.runtime` because the test file calling the macro
   might not require it directly:

     (fn [args ctx]
       (let [args args
             ctx  ctx
             a    args
             b    args]
         body))

   Args' values here are placeholders (kondo only needs the bindings
   to exist, not to reflect runtime semantics)."
  [{:keys [node]}]
  (let [[_ args-vec & body] (:children node)
        syms (:children args-vec)
        arg-pairs (mapcat (fn [s] [s (api/token-node 'args)]) syms)
        let-bindings (api/vector-node
                       (concat [(api/token-node 'args) (api/token-node 'args)
                               (api/token-node 'ctx)  (api/token-node 'ctx)]
                              arg-pairs))
        body-form (if (= 1 (count body))
                    (first body)
                    (api/list-node (cons (api/token-node 'do) body)))
        let-form (api/list-node
                   (list (api/token-node 'let) let-bindings body-form))
        new-node (api/list-node
                   (list
                     (api/token-node 'fn)
                     (api/vector-node [(api/token-node 'args) (api/token-node 'ctx)])
                     let-form))]
    {:node new-node}))
