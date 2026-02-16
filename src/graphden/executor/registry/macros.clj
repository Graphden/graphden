(ns graphden.executor.registry.macros
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
   | `:fn` | Auto-callable: `(f item)` → `((make-callable @f) item)` |

   Arguments with `:fn` type are automatically wrapped as callables that
   accept a single argument. This eliminates boilerplate in HOF definitions.

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

   ### Higher-order functions (automatic callable)

   ```clojure
   (defbase map-fn
     {:args {:f :fn, :coll :jsonb}
      :return-type :jsonb}
     (mapv f coll))
   ;; Body becomes: (mapv (make-callable @f) @coll)
   ;; No need to manually call make-single-arg-callable!
   ```

   ## Manual Definition (without macro)

   If you need full control, define functions manually:

   ```clojure
   ;; Simple function - deref args manually
   (def my-fn
     {:args {:a :int, :b :int}
      :return-type :int
      :impl (fn [{:keys [a b]} ctx]
              (+ @a @b))})

   ;; HOF with :fn arg - create callable manually
   (require '[graphden.executor.interface :as exec])

   (def my-map
     {:args {:f :fn, :coll :jsonb}
      :return-type :jsonb
      :impl (fn [{:keys [f coll]} ctx]
              (let [callable (exec/make-single-arg-callable ctx @f)]
                (mapv callable @coll)))})
   ```

   ## Context Parameter

   The execution context `ctx` is available in the function body but rarely
   needed. It's primarily used for advanced scenarios like nested execution
   or accessing storage directly.

   ## Edge Cases and Important Notes

   ### Lexical Scoping (Shadowing)

   Local bindings shadow macro arguments - they won't be dereferenced:

   ```clojure
   (defbase with-shadow
     {:args {:x :int}
      :return-type :int}
     (let [x 5]     ; x shadows the arg
       (+ x 10)))   ; Uses local x=5, NOT @x from args
   ;; Body becomes: (let [x 5] (+ x 10)) - no deref!
   ```

   This follows Clojure's lexical scoping rules. The same applies to
   `fn`, `loop`, `for`, `doseq`, `catch`, and other binding forms.

   ### Optional Arguments (nil handling)

   Arguments are wrapped with `(when arg (deref arg))` to handle optional
   args that may be nil. Delay objects are always truthy, so this correctly
   distinguishes between 'arg not provided' (nil) and 'arg provided with value'.

   ```clojure
   (defbase maybe-double
     {:args {:x {:type :int, :optional? true}}
      :return-type :any}
     (if x (* x 2) 0))
   ;; Body becomes: (if (when x @x) (* (when x @x) 2) 0)
   ```

   ### Error Handling

   Exceptions thrown during argument evaluation propagate naturally:

   ```clojure
   (defbase safe-div
     {:args {:a :int, :b :int}
      :return-type :numeric}
     (try
       (/ a b)
       (catch ArithmeticException _ 0)))
   ;; If b=0, ArithmeticException is caught and 0 is returned
   ```

   ### Performance Considerations

   - Macro expansion happens at compile time - no runtime overhead
   - Each argument reference generates a deref call; for heavily-used args
     in tight loops, consider binding to a local once:

   ```clojure
   (defbase sum-list
     {:args {:nums :jsonb}
      :return-type :numeric}
     (let [ns nums]  ; Deref once, bind to local
       (reduce + 0 ns)))
   ;; More efficient than (reduce + 0 nums) if nums appears multiple times
   ```

   ### Multi-arity and Variadic Functions

   `defbase` generates single-arity functions. For variadic behavior,
   accept a collection:

   ```clojure
   (defbase sum-all
     {:args {:nums :jsonb}  ; Accept vector of numbers
      :return-type :numeric}
     (apply + nums))
   ```"
  (:require
    [graphden.executor.interface :as exec]))


;; === Extensible Binding Forms Registry ===
;;
;; These are the built-in Clojure forms that introduce new bindings.
;; Custom binding forms (e.g., from core.async or other libraries) can be
;; registered via *custom-binding-forms*.

(def ^:private builtin-binding-forms
  "Built-in Clojure forms that introduce new variable bindings."
  #{'fn 'fn* 'let 'let* 'loop 'loop* 'letfn 'letfn*
    'for 'doseq 'with-open 'with-local-vars 'binding
    'catch})


(def ^:dynamic *custom-binding-forms*
  "Set of additional binding forms to recognize in defbase macro.
   Use this to register custom binding macros from libraries.

   Example:
   ```clojure
   ;; Register core.async binding forms
   (binding [*custom-binding-forms* #{'clojure.core.async/go 'clojure.core.async/go-loop}]
     (defbase my-fn ...))
   ```

   Each form should follow standard binding conventions where the second
   element is a binding vector."
  #{})


(defn- binding-form?
  "Returns true if form is a binding construct (fn, let, loop, etc.).
   Recognizes both built-in forms and custom forms registered in *custom-binding-forms*."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (or (builtin-binding-forms (first form))
           (*custom-binding-forms* (first form)))))


(defn- extract-bound-symbols
  "Extracts symbols bound by a binding form.
   Returns set of newly bound symbols.

   Note: For custom binding forms registered via *custom-binding-forms*, this returns
   an empty set (conservative default). This means symbol shadowing won't be detected
   for custom forms, which may result in unnecessary derefs but won't cause incorrect
   behavior. If precise shadowing detection is needed, the custom form should follow
   the let-style binding convention: (custom-form [x val y val2] body)"
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

      ;; Custom binding forms from *custom-binding-forms* - try let-style extraction
      ;; Falls back to empty set if bindings don't follow let convention
      (*custom-binding-forms* op)
      (let [bindings (second form)]
        (if (vector? bindings)
          (set (filter symbol? (take-nth 2 bindings)))
          #{}))

      :else #{})))


(defn- get-arg-type
  "Extracts type from arg spec. Handles both keyword shorthand and map form."
  [arg-spec]
  (if (map? arg-spec)
    (:type arg-spec)
    arg-spec))


(defn- transform-fn-arg-symbol
  "Transforms a :fn type argument symbol to a callable wrapper."
  [sym]
  `(exec/make-single-arg-callable ~'ctx (deref ~sym)))


(defn- transform-regular-arg-symbol
  "Transforms a regular argument symbol to deref with nil check.
   Note: (when delay (deref delay)) works because delays are always truthy."
  [sym]
  `(when ~sym (deref ~sym)))


(defn- transform-body
  "AST walker that transforms argument symbols to delayed evaluation.

   Algorithm:
   1. Recursively walks the AST (body) maintaining sets of 'active' symbols
   2. When encountering a symbol in active-args → replace with (when sym (deref sym))
   3. When encountering a symbol in active-fn-args → wrap as callable
   4. When encountering binding forms (let, fn, loop, etc.) → remove bound symbols
      from active sets for nested forms (lexical scoping)

   Transforms applied:
   - Regular args: (when arg (deref arg))
     The `when` handles optional args (nil when not provided).
     Delay object is always truthy, so this correctly derefs present args.
   - :fn type args: (exec/make-single-arg-callable ctx (deref arg))
     Creates a callable wrapper for higher-order function arguments.

   Lexical scoping:
   When a binding form shadows an argument name, the shadow takes precedence:
   (defbase foo {:args {:x :int}} (let [x 5] x))  ; x=5, not deref'd
   This is achieved by removing bound symbols from active-args before
   recursing into the binding form's body.

   Handles all Clojure data structures:
   - Lists/sequences: transform each element
   - Vectors: transform each element, preserve vector type
   - Maps: transform both keys and values
   - Sets: transform each element, preserve set type
   - Atoms (symbols, keywords, etc.): transform if in active set

   Parameters:
   - body: The form to transform
   - arg-syms: Sequence of regular argument symbols
   - fn-arg-syms: Sequence of :fn type argument symbols"
  [body arg-syms fn-arg-syms]
  (letfn [(transform-symbol
            [form active-args active-fn-args]
            (cond
              (active-fn-args form) (transform-fn-arg-symbol form)
              (active-args form) (transform-regular-arg-symbol form)
              :else form))

          (transform-binding
            [form active-args active-fn-args]
            (let [bound (extract-bound-symbols form)
                  new-active (apply disj active-args bound)
                  new-fn-active (apply disj active-fn-args bound)]
              (apply list (map #(transform % new-active new-fn-active) form))))

          (transform-map-entry
            [[k v] active-args active-fn-args]
            [(transform k active-args active-fn-args)
             (transform v active-args active-fn-args)])

          (transform
            [form active-args active-fn-args]
            (cond
              ;; Optimization: if no active args to replace, return as-is
              (and (empty? active-args) (empty? active-fn-args))
              form

              ;; Symbol transformation
              (symbol? form)
              (transform-symbol form active-args active-fn-args)

              ;; Binding form - remove shadowed symbols from active sets
              (binding-form? form)
              (transform-binding form active-args active-fn-args)

              ;; Other sequences - transform children
              (seq? form)
              (apply list (map #(transform % active-args active-fn-args) form))

              ;; Vectors - preserve type
              (vector? form)
              (mapv #(transform % active-args active-fn-args) form)

              ;; Maps - transform both keys and values
              (map? form)
              (into {} (map #(transform-map-entry % active-args active-fn-args) form))

              ;; Sets - preserve type
              (set? form)
              (set (map #(transform % active-args active-fn-args) form))

              ;; Everything else (numbers, strings, keywords, etc.) - return as-is
              :else form))]
    (transform body (set arg-syms) (set fn-arg-syms))))


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
   - Regular argument symbols in body are replaced with (when arg (deref arg))
   - :fn type arguments are auto-wrapped as callables via make-single-arg-callable
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

   (defbase map-fn
     {:args {:f :fn, :coll :jsonb}
      :return-type :jsonb}
     (mapv f coll))
   ;; Body becomes: (mapv (make-single-arg-callable ctx @f) @coll)
   ```"
  {:arglists '([name docstring? opts & body])}
  [fn-name & macro-args]
  (let [[docstring opts & body] (if (string? (first macro-args))
                                  macro-args
                                  (cons nil macro-args))
        {:keys [args return-type]} opts
        ;; Separate :fn type args from regular args
        fn-type-args (into {}
                           (filter (fn [[_k v]] (= :fn (get-arg-type v)))
                                   args))
        regular-args (into {}
                           (remove (fn [[_k v]] (= :fn (get-arg-type v)))
                                   args))
        ;; Convert keyword arg names to symbols
        all-arg-syms (for [[k _v] args]
                       (if (keyword? k) (symbol (clojure.core/name k)) k))
        fn-arg-syms (for [[k _v] fn-type-args]
                      (if (keyword? k) (symbol (clojure.core/name k)) k))
        regular-arg-syms (for [[k _v] regular-args]
                           (if (keyword? k) (symbol (clojure.core/name k)) k))
        ;; Transform body to add deref/callable at usage sites
        transformed-body (map #(transform-body % regular-arg-syms fn-arg-syms) body)
        ;; Build the impl function
        impl-fn `(fn [{:keys [~@all-arg-syms]} ~'ctx]
                   ~@transformed-body)]
    `(def ~fn-name
       ~@(when docstring [docstring])
       {:args ~args
        :return-type ~return-type
        :impl ~impl-fn
        :impl-source '~(vec body)})))


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
