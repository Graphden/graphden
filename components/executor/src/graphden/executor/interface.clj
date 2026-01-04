(ns graphden.executor.interface
  "Function graph executor.

   Executes functions stored in the graph by:
   1. Resolving the function with its arg-values and parent chain
   2. Building thunks for lazy evaluation
   3. Calling the base function with thunks

   Supports:
   - Lazy evaluation (thunks)
   - Recursion protection (max-depth)
   - Timeout protection
   - Base function registry"
  (:require
    [graphden.executor.core :as core]))


;; === Re-export Thunk Protocol ===

(def force-value
  "Forces evaluation of a thunk, returning the value.
   Context contains execution state (storage, depth, etc)."
  core/force-value)


;; === Execution Context ===

(defn create-context
  "Creates an execution context.

   Options:
   - :storage - Storage instance (required)
   - :base-fns - Map of fn-name -> fn for base function lookup (optional)
                 If not provided, uses snapshot of default global registry
   - :max-depth - Maximum recursion depth (default 1000)
   - :timeout-ms - Maximum execution time in ms (default 30000)

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

   Example:
   (register-base-fn! :add (fn [{:keys [a b]} ctx]
                             (+ (force-value a ctx) (force-value b ctx))))"
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
   - args: Map of additional arguments to provide

   Returns the result of the function execution.

   Timeout semantics:
   Timeout is checked at the START of each function call, not during execution.
   This means a long-running base function will complete fully even if it
   exceeds the timeout. For precise timeout control, base functions should
   implement their own timeout logic (e.g., using futures with deref timeout).

   Throws:
   - :execution-error/max-depth-exceeded if recursion limit is reached
   - :execution-error/timeout if execution time limit is exceeded
   - :execution-error/base-fn-not-found if base function is not registered"
  [context fn-id args]
  (core/execute context fn-id args))
