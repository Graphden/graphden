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
  (get @default-registry fn-name))


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
   Useful for passing to create-context."
  []
  @default-registry)


(defmacro with-base-fns
  "Executes body with a temporary base-fns registry.
   The registry is isolated to this execution - other threads and tests
   are not affected. After body completes, the original registry is restored.

   This is the recommended approach for parallel testing.

   Example:
   ```clojure
   (with-base-fns {:add add-fn :multiply multiply-fn}
     (let [ctx (create-context {:storage storage})]
       (execute ctx fn-id)))
   ```

   Note: This binds the default-registry atom temporarily. For complete
   isolation, pass :base-fns directly to create-context instead."
  [fns-map & body]
  `(let [old-registry# @default-registry]
     (try
       (reset! default-registry ~fns-map)
       ~@body
       (finally
         (reset! default-registry old-registry#)))))


(defn get-base-fn-from-context
  "Retrieves a base function by name from the context's registry.
   Returns the function or nil if not found.

   Note: The returned function expects arguments as delay objects."
  [context fn-name]
  (get (:base-fns context) fn-name))
