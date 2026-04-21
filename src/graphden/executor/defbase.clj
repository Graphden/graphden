(ns graphden.executor.defbase
  "`defbase` — declarative macro for base-fn implementations.

   Single source of truth for arg types/return-type is the package's
   `fns.edn`. The macro only needs to know which symbols in the body
   name args, so it takes just a bracketed list of symbols:

   ```clojure
   (defbase add [nums]
     (apply + nums))

   (defbase if-fn [test then else]
     (if test then else))

   (defbase map-fn [func coll]
     (mapv func coll))
   ```

   The macro walks the body AST and replaces each arg-symbol with
   `(rt/resolve-arg __args :arg-name)`. That helper handles:
   - Thunks (from compile.clj) → call them.
   - `IDeref` values (test-supplied delays) → deref.
   - Everything else → return as-is.

   For `:fn`-typed args (HOF), the callable is resolved via
   `rt/hof-callable` so impls uniformly write `(mapv func coll)`
   without worrying about whether `func` is a compiled closure or a
   raw fn-id.

   In-place symbol substitution preserves Clojure's natural short-circuit
   for `if`/`and`/`or`/`when`/`cond`.

   Lexical scoping: if a form shadows an arg name (`let`, `fn`, `loop`, …),
   the inner binding wins inside the shadow's scope.

   Generated signature: two-arity `(fn ([__args] …) ([__args ctx] …))`.
   `ctx` is the executor context — impls that need storage/callable
   machinery reference the `ctx` symbol directly."
  (:require
    [graphden.executor.runtime :as rt]))


;; =============================================================================
;; AST walker — transforms arg symbols in impl bodies.
;; =============================================================================

(def ^:private builtin-binding-forms
  "Clojure forms that bind local names. When we recurse into one, names it
   binds are removed from the active-args set so shadowing works."
  #{'fn 'fn* 'let 'let* 'loop 'loop* 'letfn 'letfn*
    'for 'doseq 'with-open 'with-local-vars 'binding
    'catch 'if-let 'when-let 'if-some 'when-some})


(defn- binding-form?
  [form]
  (and (seq? form)
       (symbol? (first form))
       (contains? builtin-binding-forms (first form))))


(defn- extract-bound-symbols
  "Return set of local-name symbols introduced by a binding form."
  [form]
  (let [op (first form)]
    (cond
      (#{'fn 'fn*} op)
      (let [params (if (symbol? (second form))
                     (nth form 2 nil)
                     (second form))]
        (if (vector? params)
          (set (filter symbol? (flatten params)))
          #{}))

      (#{'let 'let* 'loop 'loop* 'if-let 'when-let 'if-some 'when-some 'for 'doseq} op)
      (let [bindings (second form)]
        (if (vector? bindings)
          (set (filter symbol? (take-nth 2 bindings)))
          #{}))

      (= 'catch op)
      (if (>= (count form) 3) #{(nth form 2)} #{})

      (= 'letfn op)
      (let [fnspecs (second form)]
        (if (vector? fnspecs)
          (set (keep #(when (seq? %) (first %)) fnspecs))
          #{}))

      :else #{})))


(defn- resolve-expr
  "Emit the resolver expression for an arg symbol."
  [arg-key]
  `(rt/resolve-arg ~'__args ~arg-key))


(defn- transform-body
  "Walk `body`, replacing occurrences of `arg-syms` with their resolve
   expression, while respecting lexical shadowing by binding forms."
  [body arg-sym->key]
  (letfn [(transform
            [form active]
            (cond
              (empty? active)
              form

              (symbol? form)
              (if-let [k (get active form)]
                (resolve-expr k)
                form)

              (binding-form? form)
              (let [bound (extract-bound-symbols form)
                    active' (reduce dissoc active bound)]
                (apply list (map #(transform % active') form)))

              (seq? form)
              (apply list (map #(transform % active) form))

              (vector? form)
              (mapv #(transform % active) form)

              (map? form)
              (into {} (map (fn [[k v]]
                              [(transform k active)
                               (transform v active)]))
                    form)

              (set? form)
              (set (map #(transform % active) form))

              :else form))]
    (transform body arg-sym->key)))


;; =============================================================================
;; Macro
;; =============================================================================

(defmacro defbase
  "Declare a base-fn implementation.

   Syntax: `(defbase fn-name docstring? [arg-syms] & body)`

   The arg-syms vector names the args referenced in the body; their types
   live in the package's `fns.edn`. The body refers to args as bare symbols
   and to `ctx` for the execution context. Everything else works like
   normal Clojure — `if`/`and`/`or` short-circuit naturally."
  {:arglists '([fn-name docstring? [arg-syms] & body])}
  [fn-name & macro-args]
  (let [[docstring args-vec & body] (if (string? (first macro-args))
                                      macro-args
                                      (cons nil macro-args))
        _ (when-not (vector? args-vec)
            (throw (ex-info "defbase arg list must be a vector of symbols"
                            {:fn-name fn-name :args args-vec})))
        _ (when-not (every? simple-symbol? args-vec)
            (throw (ex-info "defbase arg list must contain only simple symbols"
                            {:fn-name fn-name :args args-vec})))
        reserved #{'__args 'ctx}
        clash (some reserved args-vec)
        _ (when clash
            (throw (ex-info (str "defbase arg name clashes with reserved symbol: " clash)
                            {:fn-name fn-name :reserved reserved})))
        arg-sym->key (into {} (map (fn [s] [s (keyword s)])) args-vec)
        transformed (transform-body body arg-sym->key)
        body-form `(do ~@transformed)]
    (if docstring
      `(defn ~fn-name
         ~docstring
         [~'__args ~'ctx] ~body-form)
      `(defn ~fn-name
         [~'__args ~'ctx] ~body-form))))
