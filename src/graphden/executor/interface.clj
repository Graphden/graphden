(ns graphden.executor.interface
  "Function graph executor — public API.

   The compile-at-startup executor lives under the hood (see
   `graphden.executor.compile` and `graphden.executor.compile-runtime`).
   Base-fn impls are authored with the `defbase` macro in
   `graphden.executor.defbase` and registered in the global registry via
   `register-base-fn!`. The executor wraps each composed fn into a
   Clojure closure at startup; `execute` looks the closure up by fn-id
   and invokes it with free-args.

   Within an impl body, use `graphden.executor.runtime/resolve-arg` to
   read arg values (or let `defbase` inline the calls for you).

   Runtime-limit enforcement (max-depth, per-call timeout, cache-size
   bounds) was part of the retired queue executor and has not yet been
   re-implemented on top of compile. Those knobs will return when the
   compile path enforces them."
  (:require
    [graphden.executor.context :as ctx]
    [graphden.executor.core :as core]
    [graphden.executor.registry :as registry]
    [graphden.executor.types :as types]))


;; === Execution Context ===

(defn create-context
  "Creates an execution context.

   Options (see `graphden.executor.context/create-context` for the full
   contract):
   - :storage   Storage instance (required).
   - :base-fns  Map of fn-name → impl-fn (optional; defaults to the
                global registry).
   - :clock     Zero-arg fn returning current time in ms (test hook)."
  [opts]
  (ctx/create-context opts))


;; === Base Functions Registry ===

(defn register-base-fn!
  "Registers a base function in the global registry.
   fn-name is a keyword (e.g. :add, :if, :map).
   f is a function that takes [args context] and returns a value.

   Impls are called with the raw args map — no delay wrapping. Use
   `rt/resolve-arg` to extract values (or the `defbase` macro in
   `graphden.executor.defbase`, which walks the body at compile time
   and substitutes arg symbols with `rt/resolve-arg` calls).

   A nil impl registers nil — matches the pre-refactor contract for
   callers passing fn-defs where `:impl` is absent.

   Example:
   (register-base-fn! :add (fn [args _]
                             (+ (rt/resolve-arg args :a)
                                (rt/resolve-arg args :b))))"
  [fn-name f]
  (registry/register-base-fn! fn-name f))


(defn get-base-fn
  "Gets a base function from the registry by name.
   Returns nil if not found."
  [fn-name]
  (registry/get-base-fn fn-name))


(defn clear-base-fns!
  "Clears all registered base functions from the global registry.
   Useful for testing."
  []
  (registry/clear-base-fns!))


(defn get-default-registry
  "Returns the current state of the default global registry as a map.
   Useful for passing to create-context.

   Example:
   (create-context {:storage s
                    :base-fns (get-default-registry)})"
  []
  (registry/get-default-registry))


(defn get-base-fn-from-context
  "Gets a base function from the context's registry by name.
   Returns nil if not found."
  [context fn-name]
  (registry/get-base-fn-from-context context fn-name))


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
  (types/register-type-hint! type-keyword hint-string))


;; === Execution ===

(defn execute
  "Executes a function by its id.

   Arguments:
   - context: Execution context (created with create-context)
   - fn-id: UUID of the function to execute
   - args: Map of arguments for FREE args only (optional, can be nil or {})
           Keys are arg-schema-ids (UUIDs).
           IMPORTANT: Can only provide values for args NOT defined in DB.
           If an arg already has a value in DB, the provided value is IGNORED
           and a warning is logged. To change an arg value, update the DB.

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


;; === HOF Helpers ===

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


(defn make-named-arg-callable
  "Creates a callable that routes the incoming value to the deep free arg
   with the given `arg-name`. Unlike `make-single-arg-callable`, this does
   not require the response-fn to have exactly one discoverable deep free
   arg — useful for domain-specific wrappers (e.g. a Ring request handler
   that always feeds the `request` arg) where the caller knows the input's
   semantic name.

   Throws :execution-error/missing-named-arg if no reachable free arg with
   that name is found."
  [context fn-id arg-name]
  (core/make-named-arg-callable context fn-id arg-name))


(defn make-optional-arg-callable
  "Creates a callable for a function with 0 or 1 required arguments.
   - 0 args: callable ignores input, calls fn with no args
   - 1 arg: callable passes input to that argument

   Used by response handlers where data-fn may or may not need request.

   Returns a function: value -> result

   Example:
   ;; For a function with 0 required args (like list-all-entities)
   (let [callable (make-optional-arg-callable ctx fn-id)]
     (callable request))  ; request is ignored, returns all entities

   ;; For a function with 1 required arg (like get-entity-details)
   (let [callable (make-optional-arg-callable ctx fn-id)]
     (callable request))  ; request is passed to the required arg

   Throws if the function has more than 1 required argument."
  [context fn-id]
  (core/make-optional-arg-callable context fn-id))


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
