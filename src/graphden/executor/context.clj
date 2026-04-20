(ns graphden.executor.context
  "Execution context for the function executor.

   Slim record after the legacy-queue retirement — only fields the
   compile-at-startup executor actually consults are kept. Re-add
   runtime-limit fields (`:max-depth`, `:timeout-ms`, result-cache
   bounds) once compile enforces them."
  (:require
    [graphden.executor.registry :as registry]
    [graphden.storage.protocol.core :as sp]))


;; === ExecutionContext Record ===

(defrecord ExecutionContext
  [storage          ; Storage instance implementing ExecutionGraph.
   base-fns         ; {fn-name-keyword → impl-fn} — read by compile.
   clock            ; Zero-arg fn returning current time in ms (testability).
   graph-cache      ; Atom: {fn-id → execution-graph} shared across requests.
   ;; `resolve-execution-graph` is deterministic per fn-id
   ;; between syncs, so memoising is safe.
   compiled-registry]) ; Atom: {fn-id → compiled-closure} or nil. Populated
;; by the compile system at startup; `execute` reads from it on the hot
;; path.


;; === Context Validation ===

(defn- validate-context-options!
  "Validates context creation options. Throws on invalid options."
  [storage]
  (cond
    (not storage)
    (throw (ex-info "Storage is required"
                    {:type :execution-error/invalid-context}))

    (not (satisfies? sp/ExecutionGraph storage))
    (throw (ex-info "storage must implement ExecutionGraph protocol"
                    {:type :execution-error/invalid-context
                     :received-type (type storage)}))))


;; === Context Creation ===

(defn create-context
  "Creates an execution context.

   Options:
   - :storage   Storage instance (required).
   - :base-fns  Map of fn-name → impl-fn (optional; defaults to the
                global registry snapshot).
   - :clock     Zero-arg fn returning current time in ms (default
                `System/currentTimeMillis`). Inject in tests for
                deterministic time."
  [{:keys [storage base-fns clock]}]
  (validate-context-options! storage)
  (->ExecutionContext storage
                      (or base-fns (registry/get-default-registry))
                      (or clock #(System/currentTimeMillis))
                      (atom {})
                      (atom nil)))


(defn resolve-graph-cached
  "Resolve an execution graph, reusing a cached result when available.
   The cache is keyed by fn-id and shared across a context's lifetime
   (created once in `create-context`, reused across all HTTP requests).
   Fn graphs don't change between syncs, so a memoised result stays
   valid as long as the JVM runs."
  [context fn-id]
  (let [cache (:graph-cache context)]
    (if (and cache (contains? @cache fn-id))
      (get @cache fn-id)
      (let [graph (sp/resolve-execution-graph (:storage context) fn-id)]
        (when cache (swap! cache assoc fn-id graph))
        graph))))


(defn current-time-ms
  "Returns current time in milliseconds using the context's clock.
   This allows for deterministic testing of timeout behavior."
  [context]
  ((:clock context)))
