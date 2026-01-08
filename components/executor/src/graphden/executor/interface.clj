(ns graphden.executor.interface
  "Function graph executor.

   Executes functions stored in the graph by:
   1. Resolving the function with its arg-values and parent chain
   2. Building delays for lazy evaluation
   3. Calling the base function with delays

   Arguments are passed to base functions as Clojure `delay` objects.
   Use @ (deref) to get the value:
     (+ @a @b)           ; for regular args
     (f {:item x})       ; for :fn args (f is a callable after deref)

   Use the defbase macro from fn-registry for convenient function definitions
   with automatic argument dereferencing.

   Supports:
   - Lazy evaluation (delays)
   - Recursion protection (max-depth)
   - Timeout protection
   - Base function registry"
  (:require
    [graphden.executor.core :as core]))


;; === Execution Context ===

(defn create-context
  "Creates an execution context.

   Options:
   - :storage - Storage instance (required)
   - :base-fns - Map of fn-name -> fn for base function lookup (optional)
                 If not provided, uses snapshot of default global registry
   - :max-depth - Maximum recursion depth (default 1000)
                  Depth is incremented for each nested function call.
                  Set lower values for tighter control over recursion.
   - :timeout-ms - Maximum execution time in ms (default 30000)
                   IMPORTANT: This is a best-effort timeout checked at the start
                   of each function call. A long-running base function will complete
                   fully even if it exceeds the timeout. For hard timeouts on
                   individual operations, base functions should use their own
                   timeout mechanisms (e.g., future with deref timeout).
   - :path-args - Map of runtime args for free arguments (optional):
                  * For root function: {arg-schema-id -> value}
                  * For nested fns via fn-result-value: {[fn-result-value-id arg-schema-id] -> value}

                  OVERRIDE BEHAVIOR (by design):
                  - Path-args can only set args that have NO value stored in DB
                  - If an arg-value exists in DB, path-arg is IGNORED (warning logged)
                  - This prevents accidental override of validated stored data
                  - To override a stored arg: use `provided-args` in `execute` call,
                    or update the arg-value in the database first

                  IMPORTANT: Direct fn refs (HOF, type=:fn) cannot receive path-args.
                  HOF functions are 'black boxes' controlled by map/reduce/etc.
                  Only functions referenced via fn-result-value can have their
                  free args set via path-args.

   Example with custom base-fns:
   (create-context {:storage s
                    :base-fns {:add my-add-fn :if my-if-fn}})

   Example with path-args for root function:
   ;; Root function has free arg with schema-id x-schema-id
   (create-context {:storage s
                    :path-args {x-schema-id 42}})

   Example with path-args for nested function via fn-result-value:
   ;; fn-result-value frv-1 references function B, which has free arg with schema-id y-schema-id
   (create-context {:storage s
                    :path-args {[frv-1 y-schema-id] 100}})"
  [opts]
  (core/create-context opts))


;; === Base Functions Registry ===

(defn register-base-fn!
  "Registers a base function in the global registry.
   fn-name is a keyword (e.g. :add, :if, :map).
   f is a function that takes [args context] and returns a value.

   Arguments are passed as delays. Use @ (deref) to get values:

   Example:
   (register-base-fn! :add (fn [{:keys [a b]} ctx]
                             (+ @a @b)))

   For :fn type args, deref returns a callable:
   (register-base-fn! :map (fn [{:keys [f coll]} ctx]
                             (mapv (fn [x] (@f {:item x})) @coll)))

   Consider using the defbase macro from fn-registry instead for
   automatic argument dereferencing."
  [fn-name f]
  (core/register-base-fn! fn-name f))


(defn get-base-fn
  "Gets a base function from the registry by name.
   Returns nil if not found."
  [fn-name]
  (core/get-base-fn fn-name))


(defn clear-base-fns!
  "Clears all registered base functions from the global registry.
   Useful for testing."
  []
  (core/clear-base-fns!))


(defn get-default-registry
  "Returns the current state of the default global registry as a map.
   Useful for passing to create-context.

   Example:
   (create-context {:storage s
                    :base-fns (get-default-registry)})"
  []
  (core/get-default-registry))


(defn get-base-fn-from-context
  "Gets a base function from the context's registry by name.
   Returns nil if not found.

   Use this when you need to look up functions from within a base function."
  [context fn-name]
  (core/get-base-fn-from-context context fn-name))


;; === Type Hints ===

(defn register-type-hint!
  "Registers a human-readable hint for a custom type.
   The hint is shown in type mismatch error messages to help users understand
   what value format is expected.

   Custom hints take precedence over built-in hints for the same type.
   This is useful when you add custom types via field-types extension.

   Example:
   (register-type-hint! :email \"string in email format (e.g., user@example.com)\")
   (register-type-hint! :phone \"string with international format (e.g., +1-555-123-4567)\")"
  [type-keyword hint-string]
  (core/register-type-hint! type-keyword hint-string))


;; === Execution ===

(defn execute
  "Executes a function by its id.

   Arguments:
   - context: Execution context (created with create-context)
   - fn-id: UUID of the function to execute
   - args: Map of additional arguments to provide (optional, can be nil or {})
           Keys are arg-schema-ids (UUIDs), values override stored arg-values.

   Returns the result of the function execution.

   Timeout semantics:
   Timeout is checked at the START of each function call, not during execution.
   This means a long-running base function will complete fully even if it
   exceeds the timeout. For precise timeout control, base functions should
   implement their own timeout logic (e.g., using futures with deref timeout).

   Example base function with hard timeout:
   (defn http-request-fn [{:keys [url timeout-ms]} ctx]
     (let [result (future (http/get (force-value url ctx)))]
       (deref result (or timeout-ms 5000) {:error :timeout})))

   Throws:
   - :execution-error/max-depth-exceeded if recursion limit is reached
   - :execution-error/timeout if execution time limit is exceeded
   - :execution-error/base-fn-not-found if base function is not registered
   - :execution-error/missing-required-arg if required argument not provided"
  [context fn-id args]
  (core/execute context fn-id args))


(defn execute-with-named-args
  "Executes a function with arguments passed by name instead of by schema-id.
   Useful for HOF functions that need to call child functions with dynamic args.

   Arguments:
   - context: Execution context (created with create-context)
   - fn-id: UUID of the function to execute
   - named-args: Map of {arg-name-keyword -> value}

   Example:
   (execute-with-named-args ctx fn-id {:item 42 :acc 0})

   This resolves :item and :acc to their respective arg-schema-ids and calls execute.

   Throws:
   - :execution-error/unknown-arg-name if an arg name doesn't exist for the function
   - All errors from execute"
  [context fn-id named-args]
  (core/execute-with-named-args context fn-id named-args))


(defn execute-by-name
  "Executes a function by its name (string).
   Convenience function that looks up the fn entity by name and executes it.

   Arguments:
   - context: Execution context (created with create-context)
   - fn-name: String name of the function to execute (e.g., \"my-add-fn\")
   - named-args: Map of {arg-name-keyword -> value} (optional, can be nil or {})

   Example:
   (execute-by-name ctx \"calculate-total\" {:items [1 2 3]})

   This looks up the fn with name \"calculate-total\", then resolves arg names
   to arg-schema-ids and executes.

   Throws:
   - :execution-error/fn-not-found if no function with the given name exists
   - :execution-error/invalid-fn-name if fn-name is not a string
   - All errors from execute-with-named-args"
  [context fn-name named-args]
  (core/execute-by-name context fn-name named-args))


;; === Optional Argument Helpers ===

(defn arg-provided?
  "Checks if an optional argument was provided in the args map.
   Use this to safely handle optional arguments in base functions.

   Optional args (`:required false` in arg-schema) are NOT included in the
   delays map when no value is provided. This helper lets you check presence
   before dereferencing.

   Arguments:
   - args: The args map passed to the base function
   - arg-name: Keyword name of the argument (e.g., :x, :default-value)

   Returns true if the argument is present in args, false otherwise.

   Example:
   (defbase my-fn
     {:args {:x :int, :y {:type :int :required false}}
      :return-type :int}
     (if (exec/arg-provided? args :y)
       (+ @x @y)
       @x))"
  [args arg-name]
  (core/arg-provided? args arg-name))


;; === HOF Helpers ===

(defn get-single-required-arg
  "Gets the single required arg-schema for a function.
   Used by HOF (map, filter, etc.) to find the target argument.

   Arguments:
   - context: Execution context with execution-graph populated
   - fn-id: UUID of the function to inspect

   Returns {:id arg-schema-id :name arg-name :type arg-type}

   Throws :execution-error/invalid-hof-function if the function doesn't have
   exactly one required argument.

   Example:
   ;; In a map implementation:
   (let [{:keys [id]} (get-single-required-arg ctx fn-id)]
     (mapv (fn [item] (execute ctx fn-id {id item})) coll))"
  [context fn-id]
  (core/get-single-required-arg context fn-id))


(defn make-single-arg-callable
  "Creates a callable for a function with exactly one required argument.
   The callable accepts a single value (not a map) and passes it to that argument.

   Used by HOF (map, filter, etc.) to call user functions without requiring
   specific argument names.

   Arguments:
   - context: Execution context with execution-graph populated
   - fn-id: UUID of the function (must have exactly 1 required arg)

   Returns a function: value -> result

   Example:
   ;; User function 'double' has one arg :x (any name works)
   (let [callable (make-single-arg-callable ctx fn-id)]
     (mapv callable [1 2 3]))  ; => [2 4 6]

   Throws :execution-error/invalid-hof-function if the function doesn't have
   exactly one required argument."
  [context fn-id]
  (core/make-single-arg-callable context fn-id))


;; === Test Fixtures ===

(defn with-clean-registry
  "Test fixture that clears the global base-fn registry before and after each test.
   Prevents test pollution from leftover registered functions.

   Usage with clojure.test:
   (use-fixtures :each exec/with-clean-registry)

   For custom setup/teardown in fixture:
   (defn my-fixture [f]
     (exec/with-clean-registry
       (fn []
         ;; custom setup
         (exec/register-base-fn! :test-fn ...)
         (f))))
   (use-fixtures :each my-fixture)"
  [f]
  (clear-base-fns!)
  (try
    (f)
    (finally
      (clear-base-fns!))))
