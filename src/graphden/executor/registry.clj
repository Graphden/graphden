(ns graphden.executor.registry
  "Base functions registry for the executor.

   The executor supports two approaches for providing base functions:

   1. **Global Registry (Convenience)**: Use register-base-fn! and friends.
      Simple for applications, but global state can cause test interference.

   2. **Context Injection (Recommended for tests)**: Pass :base-fns to create-context.
      Isolated, thread-safe, enables parallel testing without state conflicts.

   Example of context injection:
   ```clojure
   (def my-fns {:add (fn [args ctx] (+ @(:a args) @(:b args)))})
   (def ctx (create-context {:storage s :base-fns my-fns}))
   ```")


;; Default global registry for convenience.
;; For better testability, use :base-fns in create-context.
(defonce ^:private default-registry (atom {}))


;; Thread-local registry override for parallel testing.
;; When bound, get-default-registry returns this instead of the global atom.
(def ^:dynamic *thread-local-registry*
  "Thread-local override for base-fns registry.
   When non-nil, get-default-registry returns this map instead of the global registry.
   Use with-base-fns macro to bind this safely."
  nil)


(defn register-base-fn!
  "Registers a base function in the default global registry.
   fn-name - keyword identifying the function (must match fn-schema name)
   f - function taking [thunks context] and returning the result

   NOTE: This modifies global state. For isolated tests, prefer passing
   :base-fns directly to create-context instead of using the global registry.

   Thread safety: swap! is atomic, but concurrent register/clear calls
   from different tests can interfere. Use with-base-fns for isolated testing."
  [fn-name f]
  (swap! default-registry assoc fn-name f)
  nil)


(defn get-base-fn
  "Retrieves a registered base function by name from the default global registry.
   Returns the function or nil if not found.

   For context-aware lookup, use get-base-fn-from-context."
  [fn-name]
  (get (or *thread-local-registry* @default-registry) fn-name))


(defn clear-base-fns!
  "Clears all registered base functions from the default global registry.
   Primarily used in tests to reset state between test cases.

   WARNING: This affects all threads. Avoid in parallel tests.
   Prefer with-base-fns or :base-fns context injection instead."
  []
  (reset! default-registry {})
  nil)


(defn get-default-registry
  "Returns the current state of the default global registry.
   If *thread-local-registry* is bound (via with-base-fns), returns that instead.
   This allows parallel tests to have isolated registries."
  []
  (or *thread-local-registry* @default-registry))


(defmacro with-base-fns
  "Executes body with a thread-local base-fns registry.
   The registry is completely isolated to this thread - other threads and tests
   are not affected. Uses Clojure's binding which is thread-safe.

   This is the recommended approach for parallel testing.

   Example:
   ```clojure
   (with-base-fns {:add add-fn :multiply multiply-fn}
     (let [ctx (create-context {:storage storage})]
       (execute ctx fn-id)))
   ```

   Thread safety: Uses dynamic binding, so each thread gets its own registry.
   Parallel tests can safely use different base-fns without interference."
  [fns-map & body]
  `(binding [*thread-local-registry* ~fns-map]
     (let [res# (do ~@body)]
       res#)))


(defmacro with-isolated-registry
  "Executes body with an isolated copy of the global registry.
   Any modifications to the registry during body execution will be
   reverted when body completes (even if an exception is thrown).

   Use this when you need to test code that calls register-base-fn!
   without polluting the global registry for other tests.

   Example:
   ```clojure
   (with-isolated-registry
     (register-base-fn! :my-fn my-impl)
     (test-something-that-uses-registry))
   ;; Registry is restored to original state here
   ```

   Note: For most test cases, prefer with-base-fns which uses thread-local
   binding. Use with-isolated-registry only when testing code that
   explicitly modifies the global registry via register-base-fn!."
  [& body]
  `(let [registry# @#'default-registry
         saved# @registry#]
     (try
       (do ~@body)
       (finally
         (reset! registry# saved#)))))


(defn get-base-fn-from-context
  "Retrieves a base function by name from the context's registry.
   Returns the function or nil if not found.

   Note: The returned function expects arguments as delay objects."
  [context fn-name]
  (get (:base-fns context) fn-name))
