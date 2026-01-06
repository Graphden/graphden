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

   Example with custom base-fns:
   (create-context {:storage s
                    :base-fns {:add my-add-fn :if my-if-fn}})"
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
