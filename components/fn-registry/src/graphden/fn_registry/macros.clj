(ns graphden.fn-registry.macros
  "Macros for defining base functions with automatic lazy argument handling.

   ## Overview

   The `defbase` macro simplifies base function definitions by automatically
   handling argument dereferencing. All arguments are passed as `delay` objects
   by the executor, and `defbase` transforms the body to dereference arguments
   at their usage sites, preserving Clojure's natural short-circuit evaluation.

   ## Quick Start

   ```clojure
   (defbase add
     \"Adds numbers together.\"
     {:args {:nums :jsonb}
      :return-type :numeric}
     (apply + nums))
   ```

   This expands to a function where `nums` is dereferenced at the point of use.

   ## How It Works

   The macro walks the function body and replaces argument symbols with deref
   calls. This means:

   ```clojure
   (defbase if-fn
     {:args {:condition :bool, :then :any, :else :any}
      :return-type :any}
     (if condition then else))
   ```

   Expands to:
   ```clojure
   (if @condition @then @else)
   ```

   Because deref happens at usage sites (not upfront in let-bindings),
   Clojure's native short-circuit evaluation works correctly - only the
   needed branch is evaluated.

   ## Argument Types

   | Arg Type | In Body |
   |----------|---------|
   | `:int`, `:text`, `:bool`, etc. | Use directly: `(+ a b)` → `(+ @a @b)` |
   | `:jsonb` | Use directly: `(first coll)` → `(first @coll)` |
   | `:fn` | Callable: `(f {:item x})` → `(@f {:item x})` |

   All arguments are automatically dereferenced at their usage sites.

   ## Examples

   ### Simple function

   ```clojure
   (defbase add
     {:args {:a :int, :b :int}
      :return-type :int}
     (+ a b))
   ;; Body becomes: (+ @a @b)
   ```

   ### Conditional (short-circuit preserved)

   ```clojure
   (defbase if-fn
     {:args {:condition :bool, :then :any, :else :any}
      :return-type :any}
     (if condition then else))
   ;; Body becomes: (if @condition @then @else)
   ;; Only one of @then or @else is evaluated!
   ```

   ### Higher-order functions

   ```clojure
   (defbase map-fn
     {:args {:f :fn, :coll :jsonb}
      :return-type :jsonb}
     (mapv (fn [item] (f {:item item})) coll))
   ;; Body becomes: (mapv (fn [item] (@f {:item item})) @coll)
   ```

   ## Manual Definition (without macro)

   If you need full control, define functions manually:

   ```clojure
   (def my-fn
     {:args {:a :int, :b :int}
      :return-type :int
      :impl (fn [{:keys [a b]} ctx]
              (+ @a @b))})
   ```

   ## Context Parameter

   The execution context `ctx` is available in the function body but rarely
   needed. It's primarily used for advanced scenarios like nested execution
   or accessing storage directly.")


(defn- binding-form?
  "Returns true if form is a binding construct (fn, let, loop, etc.)."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (#{'fn 'fn* 'let 'let* 'loop 'loop* 'letfn 'letfn*
          'for 'doseq 'with-open 'with-local-vars 'binding
          'catch} (first form))))


(defn- extract-bound-symbols
  "Extracts symbols bound by a binding form.
   Returns set of newly bound symbols."
  [form]
  (let [op (first form)]
    (cond
      ;; fn/fn*: (fn [x y] ...) or (fn name [x y] ...)
      (#{'fn 'fn*} op)
      (let [params (if (symbol? (second form))
                     (nth form 2 nil)  ; named fn
                     (second form))]   ; anonymous fn
        (if (vector? params)
          (set (filter symbol? (flatten params)))
          #{}))

      ;; let/let*/loop/loop*: (let [x 1 y 2] ...)
      (#{'let 'let* 'loop 'loop*} op)
      (let [bindings (second form)]
        (if (vector? bindings)
          (set (take-nth 2 bindings))
          #{}))

      ;; for/doseq: (for [x coll] ...)
      (#{'for 'doseq} op)
      (let [bindings (second form)]
        (if (vector? bindings)
          (set (filter symbol? (take-nth 2 bindings)))
          #{}))

      ;; catch: (catch Exception e ...)
      (#{'catch} op)
      (if (>= (count form) 3)
        #{(nth form 2)}
        #{})

      :else #{})))


(defn- transform-body
  "Walks the body and replaces argument symbols with (deref arg) calls.
   Handles nil args safely with (when arg (deref arg)) pattern.
   Respects lexical scope - does not replace symbols that are shadowed
   by local bindings (fn params, let bindings, etc.)."
  [body arg-syms]
  (letfn [(transform
            [form active-args]
            (cond
              ;; If no active args to replace, return as-is
              (empty? active-args)
              form

              ;; Symbol that should be replaced
              (and (symbol? form) (active-args form))
              `(when ~form (deref ~form))

              ;; Binding form - remove shadowed symbols from active set
              (binding-form? form)
              (let [bound (extract-bound-symbols form)
                    new-active (apply disj active-args bound)]
                ;; Recursively transform children with updated active set
                (apply list (map #(transform % new-active) form)))

              ;; Other sequences - transform children
              (seq? form)
              (apply list (map #(transform % active-args) form))

              ;; Vectors
              (vector? form)
              (mapv #(transform % active-args) form)

              ;; Maps
              (map? form)
              (into {} (map (fn [[k v]]
                              [(transform k active-args)
                               (transform v active-args)])
                            form))

              ;; Sets
              (set? form)
              (set (map #(transform % active-args) form))

              ;; Everything else - return as-is
              :else form))]
    (transform body (set arg-syms))))


(defmacro defbase
  "Defines a base function with automatic argument handling.

   Arguments:
   - name: Symbol for the function definition
   - docstring: Optional documentation string
   - opts: Map with keys:
     - :args - Map of {arg-name arg-type} (required)
     - :return-type - Return type keyword (required)
   - body: Function body expressions

   Behavior:
   - All argument symbols in body are replaced with (deref arg)
   - Deref happens at usage site, preserving short-circuit evaluation
   - The symbol `ctx` is bound to execution context in body

   Example:
   ```clojure
   (defbase add
     \"Adds two numbers.\"
     {:args {:a :int, :b :int}
      :return-type :int}
     (+ a b))
   ;; Body becomes: (+ @a @b)
   ```"
  {:arglists '([name docstring? opts & body])}
  [fn-name & macro-args]
  (let [[docstring opts & body] (if (string? (first macro-args))
                                  macro-args
                                  (cons nil macro-args))
        {:keys [args return-type]} opts
        ;; Convert keyword arg names to symbols for use in generated code
        arg-syms (for [[k _v] args]
                   (if (keyword? k) (symbol (clojure.core/name k)) k))
        ;; Transform body to add deref at usage sites
        transformed-body (map #(transform-body % arg-syms) body)
        ;; Build the impl function
        impl-fn `(fn [{:keys [~@arg-syms]} ~'ctx]
                   ~@transformed-body)]
    `(def ~fn-name
       ~@(when docstring [docstring])
       {:args ~args
        :return-type ~return-type
        :impl ~impl-fn})))


(comment
  ;; Example expansions:

  ;; Simple function - args are deref'd at usage sites
  (macroexpand-1
    '(defbase add
       {:args {:a :int, :b :int}
        :return-type :int}
       (+ a b)))
  ;; =>
  ;; (def add
  ;;   {:args {:a :int, :b :int}
  ;;    :return-type :int
  ;;    :impl (fn [{:keys [a b]} ctx]
  ;;            (+ @a @b))})

  ;; Conditional - short-circuit works naturally!
  (macroexpand-1
    '(defbase if-fn
       {:args {:condition :bool, :then :any, :else :any}
        :return-type :any}
       (if condition then else)))
  ;; =>
  ;; (def if-fn
  ;;   {:args {:condition :bool, :then :any, :else :any}
  ;;    :return-type :any
  ;;    :impl (fn [{:keys [condition then else]} ctx]
  ;;            (if @condition @then @else))})
  ;; Note: Clojure's `if` only evaluates one branch, so only @then OR @else runs

  ;; HOF with :fn type
  (macroexpand-1
    '(defbase map-fn
       {:args {:f :fn, :coll :jsonb}
        :return-type :jsonb}
       (mapv (fn [item] (f {:item item})) coll)))
  ;; =>
  ;; (def map-fn
  ;;   {:args {:f :fn, :coll :jsonb}
  ;;    :return-type :jsonb
  ;;    :impl (fn [{:keys [f coll]} ctx]
  ;;            (mapv (fn [item] (@f {:item item})) @coll))})

  )
