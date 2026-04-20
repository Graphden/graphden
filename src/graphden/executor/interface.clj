(ns graphden.executor.interface
  "Function graph executor.

   Executes functions stored in the graph by:
   1. Resolving the function with its arg-values
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
   - Base function registry

   ## Performance Tuning Guide

   The executor has several configurable limits that interact with each other.
   Understanding these interactions helps optimize for different workloads.

   ### Limit Interaction Matrix

   | Limit           | Default  | Affects                | First to hit         |
   |-----------------|----------|------------------------|----------------------|
   | :max-depth      | 1000     | Recursion depth        | Deep graphs          |
   | :timeout-ms     | 30000    | Total execution time   | Complex computations |
   | :cache-max-size | 10000    | Result cache entries   | Wide graphs          |

   **Which limit fires first?**

   - **Deep linear chains** (A→B→C→...→Z): :max-depth fires first
   - **Wide branching graphs** (A calls B,C,D,...,Z): :cache-max-size fires first
   - **Slow base functions** (API calls, heavy computation): :timeout-ms fires first

   ### Recommended Values by Workload

   **Simple functions** (typical use):
   ```clojure
   {:max-depth 100
    :timeout-ms 5000
    :cache-max-size 1000}
   ```

   **Deep recursive graphs** (>50 levels):
   ```clojure
   {:max-depth 1000
    :timeout-ms 30000
    :cache-max-size 5000}
   ```

   **Wide parallel graphs** (map over large collections):
   ```clojure
   {:max-depth 100
    :timeout-ms 60000
    :cache-max-size 50000}
   ```

   **API-bound functions** (external service calls):
   ```clojure
   {:max-depth 50
    :timeout-ms 120000  ; 2 minutes for slow APIs
    :cache-max-size 1000}
   ```

   ### Warning Thresholds

   The executor logs warnings at 80% of each limit:

   - `Approaching max recursion depth` - Consider simplifying graph structure
   - `Approaching execution timeout` - Consider async execution
   - `Result cache size reached warning threshold` - Consider limiting graph depth

   ### Cache Behavior

   Result cache stores fn-usage computations for memoization.

   - **Cache hit**: O(1) lookup, no recomputation
   - **Cache miss**: Execute function, store result
   - **Cache full**: Evict 20% oldest entries (LRU-like)

   **Memory estimation**: ~1KB per cached value average.
   Default 10,000 entries ≈ 10MB memory overhead.

   ### Monitoring

   Enable debug logging to see:
   - Cache hit/miss events
   - Depth/timeout warnings at 80% threshold
   - Cache eviction events"
  (:require
    [graphden.executor.context :as ctx]
    [graphden.executor.core :as core]
    [graphden.executor.registry :as registry]
    [graphden.executor.runtime :as rt]
    [graphden.executor.types :as types]))


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
  (ctx/create-context opts))


;; === Base Functions Registry ===

(defn- adapt-legacy-args
  "Return a map view of `args` whose every value is a `Delay` that forces
   via `rt/resolve-arg`. Keys present in `args` get their resolved value;
   keys NOT present yield a `(delay nil)`, matching legacy SmartDelay
   semantics for unprovided-optional args so bodies like
   `(when-let [s @suffix] …)` keep working.

   Returns a `PersistentHashMap` (via `with-meta`-compatible construction)
   so `contains?`, destructuring, `seq`, and `count` all behave like a
   normal map. The downside: we have to enumerate keys up front. We cover
   every key in `args`; missing keys resolve lazily via a default fallback
   proxy below."
  [args]
  (let [nil-delay (delay nil)]
    (reify
      clojure.lang.ILookup
      (valAt
        [_ k]
        (if (contains? args k)
          (delay (rt/resolve-arg args k))
          nil-delay))

      (valAt
        [_ k not-found]
        (if (contains? args k)
          (delay (rt/resolve-arg args k))
          not-found))


      clojure.lang.Associative

      (containsKey [_ k] (contains? args k))

      (entryAt
        [_ k]
        (when (contains? args k)
          (clojure.lang.MapEntry/create k (delay (rt/resolve-arg args k)))))

      (assoc [_ _ _] (throw (UnsupportedOperationException. "read-only")))


      clojure.lang.IPersistentCollection

      (count [_] (count args))

      (cons [_ _] (throw (UnsupportedOperationException. "read-only")))

      (empty [_] (throw (UnsupportedOperationException. "read-only")))

      (equiv [_ _] false)


      clojure.lang.Seqable

      (seq
        [_]
        (seq (map (fn [[k _v]]
                    (clojure.lang.MapEntry/create k (delay (rt/resolve-arg args k))))
                  args)))


      clojure.lang.IFn

      (invoke
        [_ k]
        (if (contains? args k)
          (delay (rt/resolve-arg args k))
          nil-delay))

      (invoke
        [_ k not-found]
        (if (contains? args k)
          (delay (rt/resolve-arg args k))
          not-found)))))


(defn- wrap-legacy-derefs
  "Wrap an impl so bodies that use the legacy `@arg` deref pattern still
   work under the compile executor. The compile path passes thunks
   (for refs), plain values (for literals / free-args), or IDeref
   (rarely); the adapter re-wraps each arg as a Delay that forces via
   `rt/resolve-arg`. Production impls from `defbase` bypass this — they
   use `rt/resolve-arg` directly, which handles all three shapes.

   The raw impl is attached as `:raw-fn` metadata so callers that
   identity-compare can reach it via `get-base-fn`."
  [f]
  (with-meta
    (fn [args ctx] (f (adapt-legacy-args args) ctx))
    {:raw-fn f}))


(defn- unwrap
  "Return the raw user-registered impl (bypassing the legacy-deref
   adapter) when the wrapper carries `:raw-fn` metadata."
  [f]
  (or (some-> f meta :raw-fn) f))


(defn register-base-fn!
  "Registers a base function in the global registry.
   fn-name is a keyword (e.g. :add, :if, :map).
   f is a function that takes [args context] and returns a value.

   Arguments are wrapped as `delay` objects so legacy-style `@arg`
   deref still works. Prefer the `defbase` macro in
   `graphden.executor.defbase` — it walks the body at compile time and
   substitutes arg symbols with `rt/resolve-arg` calls, no manual deref
   required.

   A nil impl registers nil (no-op), matching the pre-refactor contract
   that callers relying on `:impl` being absent still get `nil` back.

   Example (legacy-style, still supported):
   (register-base-fn! :add (fn [{:keys [a b]} ctx]
                             (+ @a @b)))"
  [fn-name f]
  (registry/register-base-fn! fn-name (when f (wrap-legacy-derefs f))))


(defn get-base-fn
  "Gets a base function from the registry by name.
   Returns nil if not found. Returns the user's raw impl (not the
   internal legacy-deref adapter) so identity comparisons match."
  [fn-name]
  (some-> (registry/get-base-fn fn-name) unwrap))


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
   Returns nil if not found. Unwraps the legacy-deref adapter so the
   returned value identity-matches what callers passed to
   `register-base-fn!`."
  [context fn-name]
  (some-> (registry/get-base-fn-from-context context fn-name) unwrap))


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


;; === Context Utilities ===

(defn clear-result-cache!
  "Clears the result cache in the given context.
   Useful for long-running applications that reuse contexts across multiple executions.

   Returns the number of entries that were cleared.

   Example:
   (let [ctx (create-context {:storage s})]
     (execute ctx fn-id-1 nil)
     (clear-result-cache! ctx)  ; Clear before next independent execution
     (execute ctx fn-id-2 nil))"
  [context]
  (ctx/clear-result-cache! context))


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
