(ns graphden.executor.compile-runtime
  "Public entry points for the compiled executor.

   Bridges between the compile-time registry (produced by
   `graphden.executor.compile/compile-all`) and the executor's public API
   (`execute`, `make-*-arg-callable`).

   Since the legacy queue was retired, this namespace IS the executor —
   `exec/` public API delegates here. The registry is rebuilt on demand
   when missing (test paths that create contexts directly without going
   through the system-level `:exec/compiled-registry` init-key)."
  (:require
    [graphden.executor.compile :as compile]
    [graphden.executor.runtime :as rt]
    [graphden.storage.protocol.core :as sp]))


;; =============================================================================
;; Registry lifecycle
;; =============================================================================

(defn rebuild!
  "Rebuild the compiled registry in `ctx` from whatever fns/args storage
   currently holds. Call at startup and on invalidation."
  [ctx]
  (let [storage (:storage ctx)
        fns (sp/query-entities storage :fn {})
        args (sp/query-entities storage :arg {})
        base-fns (:base-fns ctx)
        registry (compile/compile-all {:fns fns :args args :base-fns base-fns} ctx)]
    (reset! (:compiled-registry ctx) registry)
    registry))


(defn registry
  "Return the current compiled registry from `ctx`, rebuilding on-demand
   when missing. Tests that skip the `:exec/compiled-registry` init-key
   still get a working executor via this fallback — the cost is a single
   compile pass on first execution."
  [ctx]
  (when-let [holder (:compiled-registry ctx)]
    (or @holder (rebuild! ctx))))


(defn compiled?
  [ctx]
  (some? (registry ctx)))


;; =============================================================================
;; Arg-name resolution
;; =============================================================================

(defn- build-arg-map
  [storage]
  (into {} (map (juxt :id identity)) (sp/query-entities storage :arg {})))


(defn free-arg-ext-names
  "Return the ordered vector of external names for fn-id's free args
   reachable through its ref-chain. Used to shape HOF callables when the
   caller didn't pick a specific arg name."
  [ctx fn-id]
  (let [storage (:storage ctx)
        fns (sp/query-entities storage :fn {})
        args (sp/query-entities storage :arg {})
        lookups (assoc (compile/build-lookups fns args)
                       :base-fns (:base-fns ctx))]
    (mapv :ext-name
          (filter #(= :free (:kind %))
                  (compile/collect-bindings fn-id lookups)))))


;; =============================================================================
;; Execute
;; =============================================================================

(defn execute
  "Invoke `fn-id` via the compiled registry. `named-args` is a `{arg-name
   value}` map using the outermost external arg names (rename-aware).

   If `fn-id` is actually a fn object (e.g. a `hof-wrap`-produced
   callable that a legacy-style HOF impl extracted via `@fn-arg`),
   invoke it directly. For single-entry named-args the value is
   unwrapped (matching the 1-arg callable shape); otherwise the whole
   map is forwarded. This keeps legacy test patterns working where the
   impl does `(exec/execute-with-named-args ctx @f {:x x})`."
  [ctx fn-id named-args]
  (cond
    (fn? fn-id)
    (let [args (or named-args {})]
      (if (and (map? args) (= 1 (count args)))
        (fn-id (first (vals args)))
        (fn-id args)))

    :else
    (let [reg (registry ctx)
          closure (get reg fn-id)]
      (when-not closure
        (throw (ex-info (str "Function not found: " fn-id)
                        {:type :execution-error/fn-not-found
                         :fn-id fn-id})))
      (closure reg (or named-args {})))))


(defn- query-fn-by-name
  "Storage schemas vary on whether `fn.name` is stored as text or enum
   (package-loader goes through a keyword codec). Try both shapes and
   swallow validation errors so either works."
  [storage fn-name]
  (letfn [(try-one
            [value]
            (try
              (first (sp/query-entities storage :fn {:name value}))
              (catch clojure.lang.ExceptionInfo e
                (when-not (= :validation-error/type-mismatch
                             (:type (ex-data e)))
                  (throw e))
                nil)))]
    (or (try-one fn-name)
        (try-one (keyword fn-name)))))


(defn execute-by-name
  [ctx fn-name named-args]
  (let [match (query-fn-by-name (:storage ctx) fn-name)]
    (when-not match
      (throw (ex-info (str "Function '" fn-name "' not found")
                      {:type :execution-error/fn-not-found
                       :fn-name fn-name})))
    (execute ctx (:id match) named-args)))


(defn execute-with-arg-ids
  "Execute using a `{arg-id → value}` map (legacy test-code style).
   Converts arg-ids to their external names via `compile/arg-ext-name`
   and delegates to `execute`. Most callers should use `execute` directly
   with name-keyed args; this shim keeps pre-compile-era tests working."
  [ctx fn-id arg-id-map]
  (if (empty? arg-id-map)
    (execute ctx fn-id {})
    (let [arg-map (build-arg-map (:storage ctx))
          named (reduce-kv (fn [acc arg-id v]
                             (if-let [n (compile/arg-ext-name arg-id arg-map)]
                               (assoc acc n v)
                               acc))
                           {}
                           arg-id-map)]
      (execute ctx fn-id named))))


;; =============================================================================
;; HOF callable helpers
;; =============================================================================

(defn make-single-arg-callable
  "Build a `(fn [item] result)` callable over `fn-id`. Routes the item to
   the target's single free arg (or to `:request` if present, matching
   compile's `hof-wrap` `:request` special-case for Ring handlers).

   If `fn-id` is already a callable (e.g. a compile-produced `hof-wrap`
   result handed to a legacy-style impl that calls this helper), returns
   it as-is."
  [ctx fn-id]
  (if (fn? fn-id)
    fn-id
    (let [reg (registry ctx)
          closure (get reg fn-id)
          free-names (free-arg-ext-names ctx fn-id)]
      (when-not closure
        (throw (ex-info (str "Function not found: " fn-id)
                        {:type :execution-error/fn-not-found
                         :fn-id fn-id})))
      (cond
        (some #{:request} free-names)
        (fn [item] (closure reg {:request item}))

        (zero? (count free-names))
        (fn [& _] (closure reg {}))

        (= 1 (count free-names))
        (let [n (first free-names)]
          (fn [item] (closure reg {n item})))

        :else
        (let [names (vec free-names)]
          (fn [items] (closure reg (zipmap names items))))))))


;; Re-export — so this namespace is the canonical entry surface.
(def thunk rt/thunk)
(def resolve-arg rt/resolve-arg)
