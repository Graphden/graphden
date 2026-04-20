(ns graphden.executor.compile-runtime
  "Public entry points for the compiled executor.

   Bridges between the compile-time registry (produced by
   `graphden.executor.compile/compile-all`) and the executor's public API
   (`execute`, `make-*-arg-callable`). When the execution context has a
   non-empty `:compiled-registry`, these entry points use it directly —
   no graph resolution, no trampolined queue, no SmartDelay allocation.

   Call sites that don't populate the registry (most unit tests) fall
   through to the legacy queue — see `executor/core.clj`."
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
  "Return the current compiled registry from `ctx`, or nil if none."
  [ctx]
  (some-> (:compiled-registry ctx) deref))


(defn compiled?
  [ctx]
  (some? (registry ctx)))


;; =============================================================================
;; Hot-path execute
;; =============================================================================

(defn execute
  "Invoke `fn-id` via the compiled registry. `named-args` is a `{arg-name
   value}` map using the outermost external arg names (rename-aware). No
   DB queries, no SmartDelay, no queue."
  [ctx fn-id named-args]
  (let [reg (registry ctx)
        closure (get reg fn-id)]
    (when-not closure
      (throw (ex-info "Fn not in compiled registry"
                      {:type :execution-error/fn-not-compiled
                       :fn-id fn-id})))
    (closure reg (or named-args {}))))


(defn execute-by-name
  [ctx fn-name named-args]
  (let [storage (:storage ctx)
        ;; Storage column accepts either string or keyword depending on backend
        ;; — try both.
        match (or (first (sp/query-entities storage :fn {:name fn-name}))
                  (first (sp/query-entities storage :fn {:name (keyword fn-name)})))]
    (when-not match
      (throw (ex-info (str "Fn '" fn-name "' not found in storage")
                      {:type :execution-error/fn-not-found
                       :fn-name fn-name})))
    (execute ctx (:id match) named-args)))


;; =============================================================================
;; HOF callable helpers (compile-backed replacements for make-*-arg-callable)
;; =============================================================================

(defn make-single-arg-callable
  "Build a `(fn [item] result)` callable over `fn-id` using the compiled
   registry. Used by base-fns that need to feed values into a user-provided
   HOF target (map/filter/reduce/etc.)."
  [ctx fn-id]
  (let [reg (registry ctx)
        closure (get reg fn-id)
        free-names (mapv :ext-name
                         (filter #(= :free (:kind %))
                                 (compile/collect-bindings
                                   fn-id
                                   (assoc (compile/build-lookups
                                            (sp/query-entities (:storage ctx) :fn {})
                                            (sp/query-entities (:storage ctx) :arg {}))
                                          :base-fns (:base-fns ctx)))))]
    (case (count free-names)
      0 (fn [] (closure reg {}))
      1 (let [n (first free-names)]
          (fn [item] (closure reg {n item})))
      (let [names free-names]
        (fn [items] (closure reg (zipmap names items)))))))


;; Re-export — so this namespace is the canonical entry surface.
(def thunk rt/thunk)
(def resolve-arg rt/resolve-arg)
