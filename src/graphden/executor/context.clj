(ns graphden.executor.context
  "Execution context for the function executor."
  (:require
    [graphden.executor.registry :as registry]
    [graphden.storage.protocol.core :as sp]))


;; === ExecutionContext Record ===

(defrecord ExecutionContext
  [storage          ; Storage instance implementing ExecutionGraph.
   base-fns         ; {fn-name-keyword → impl-fn} — read by compile.
   clock            ; Zero-arg fn returning current time in ms (testability).
   compiled-registry ; Atom: {fn-id → compiled-closure} or nil. Populated
   ;; by the compile system at startup; `execute` reads from it on the hot
   ;; path.
   graph-cache])    ; Atom holding `{:fns [...] :args [...]}` loaded from
;; storage. Populated lazily by read-heavy consumers (e.g. the layout API).
;; Invalidated by CRUD mutations that change fn/arg entities via
;; `invalidate-graph-cache!`. Nil before first load.


(defn invalidate-graph-cache!
  "Clear the raw graph-entities cache on `ctx`. Call after any mutation
   that writes/deletes fn or arg entities so read paths reload on next
   request."
  [ctx]
  (when-let [c (:graph-cache ctx)]
    (reset! c nil)))


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
                      (atom nil)
                      (atom nil)))


(defn current-time-ms
  "Returns current time in milliseconds using the context's clock.
   This allows for deterministic testing of timeout behavior."
  [context]
  ((:clock context)))
