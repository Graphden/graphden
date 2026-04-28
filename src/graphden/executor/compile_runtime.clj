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
    [graphden.executor.compile.bindings :as b]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.runtime :as rt]
    [graphden.storage.protocol.core :as sp]))


;; =============================================================================
;; Registry lifecycle
;; =============================================================================

(defn rebuild!
  "Rebuild the compiled registry in `ctx` from whatever fns/args storage
   currently holds. Also primes `:graph-cache` with the same raw entities
   so read-heavy consumers (e.g. the layout API) can reuse them without
   re-querying storage. Call at startup and on invalidation."
  [ctx]
  (let [storage (:storage ctx)
        fns (sp/query-entities storage :fn {})
        args (sp/query-entities storage :arg {})
        base-fns (:base-fns ctx)
        registry (compile/compile-all {:fns fns :args args :base-fns base-fns} ctx)]
    (reset! (:compiled-registry ctx) registry)
    (when-let [graph-cache (:graph-cache ctx)]
      (reset! graph-cache {:fns (vec fns) :args (vec args)}))
    registry))


(defn registry
  "Return the current compiled registry from `ctx`, rebuilding on-demand
   when missing. Tests that skip the `:exec/compiled-registry` init-key
   still get a working executor via this fallback — the cost is a single
   compile pass on first execution."
  [ctx]
  (when-let [holder (:compiled-registry ctx)]
    (or @holder (rebuild! ctx))))


;; =============================================================================
;; Arg-name resolution
;; =============================================================================

(defn free-arg-ext-names
  "Return the ordered vector of external names for fn-id's free args
   reachable through its ref-chain. Used to shape HOF callables when the
   caller didn't pick a specific arg name."
  [ctx fn-id]
  (let [storage (:storage ctx)
        fns (sp/query-entities storage :fn {})
        args (sp/query-entities storage :arg {})
        lookups (assoc (l/build-lookups fns args)
                       :base-fns (:base-fns ctx))]
    (mapv :ext-name
          (filter #(= :free (:kind %))
                  (b/collect-bindings fn-id lookups)))))


;; =============================================================================
;; Execute
;; =============================================================================

(defn execute
  "Invoke `fn-id` via the compiled registry. `named-args` is a `{arg-name
   value}` map using the outermost external arg names (rename-aware).

   HOF impls that deref a `:fn`-type arg end up with a callable (from
   `rt/hof-callable`) rather than a UUID and hand it back in through
   this same entry point. For single-entry args the value is unwrapped
   from the map; for empty or multi-entry args the whole map is passed
   through."
  [ctx fn-id named-args]
  (if (fn? fn-id)
    (let [args (or named-args {})]
      (if (= 1 (count args))
        (fn-id (first (vals args)))
        (fn-id args)))
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


;; =============================================================================
;; HOF callable helpers
;; =============================================================================

(defn make-single-arg-callable
  "Build a callable over `fn-id`. Mirrors `compile/hof-wrap`'s
   leftover-logic: 0 free args → variadic ignore; 1 free arg →
   single-arg callable (item bound to that name); 2+ → map-callable
   (caller passes `{name value}` map matching the target's free-arg
   names). The compiler picks no names — author and caller agree.

   If `fn-id` is already a callable (e.g. a compile-produced wrap
   result handed to a helper that calls this), returns it as-is.

   This entry point has no `outer-free-args` to subtract — it builds
   a top-level callable. So `leftover` here is the full set of
   free-arg names of the target."
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
      (case (count free-names)
        0 (fn [& _] (closure reg {}))
        1 (let [n (first free-names)]
            (fn [item] (closure reg {n item})))
        (fn [m] (closure reg m))))))


;; Re-export — so this namespace is the canonical entry surface.
(def thunk rt/thunk)
(def resolve-arg rt/resolve-arg)
