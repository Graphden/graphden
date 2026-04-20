(ns graphden.executor.core
  "Thin shim over `graphden.executor.compile-runtime`.

   The legacy trampolined queue was retired in favor of compile-at-startup;
   this namespace exists only to preserve the public `exec/` API shape
   (`execute`, `execute-with-named-args`, `execute-by-name`, and the
   `make-*-callable` HOF helpers) so downstream callers and the test
   suite don't need to re-import."
  (:require
    [graphden.executor.compile-runtime :as cr]))


;; === Execution ===

(defn execute
  "Executes a function by id. `args` may be `nil`, `{}`, a map keyed by
   arg-id (legacy test style — converted via `arg-ext-name` walk), or a
   map keyed by external arg name (preferred)."
  [context fn-id args]
  (when (and (some? args) (not (map? args)))
    (throw (ex-info "args must be nil or a map"
                    {:type :execution-error/invalid-args
                     :args args
                     :args-type (type args)})))
  (cond
    (or (nil? args) (empty? args))
    (cr/execute context fn-id {})

    ;; Keyed by arg-id (UUID) — legacy test style.
    (uuid? (first (keys args)))
    (cr/execute-with-arg-ids context fn-id args)

    ;; Keyed by external arg name.
    :else
    (cr/execute context fn-id args)))


(defn execute-with-named-args
  "Executes a function with arguments passed by name. Unknown names
   (not among the fn's free-arg external names) throw with the legacy
   `Unknown argument name` message — this keeps external callers
   honest without policing hof-wrap's internal over-filling.

   Validation is skipped when `fn-id` is actually a callable (legacy
   HOF impls deref `:fn` args and pass the resulting callable here);
   callable shape is enforced by `cr/execute`'s own dispatch."
  [context fn-id named-args]
  (when (and (some? named-args) (not (map? named-args)))
    (throw (ex-info "named-args must be nil or a map"
                    {:type :execution-error/invalid-args
                     :args named-args
                     :args-type (type named-args)})))
  (when (and (seq named-args) (uuid? fn-id))
    (let [valid (set (cr/free-arg-ext-names context fn-id))]
      (when-let [unknown (first (remove valid (keys named-args)))]
        (throw (ex-info (str "Unknown argument name: " unknown)
                        {:type :execution-error/unknown-arg-name
                         :arg-name unknown
                         :fn-id fn-id
                         :available-args valid})))))
  (cr/execute context fn-id (or named-args {})))


(defn execute-by-name
  "Executes a function by its name (string)."
  [context fn-name named-args]
  (when-not (string? fn-name)
    (throw (ex-info "fn-name must be a string"
                    {:type :execution-error/invalid-fn-name
                     :fn-name fn-name
                     :fn-name-type (type fn-name)})))
  (cr/execute-by-name context fn-name named-args))


;; === HOF helpers ===

(defn make-single-arg-callable
  [context fn-id]
  (cr/make-single-arg-callable context fn-id))
