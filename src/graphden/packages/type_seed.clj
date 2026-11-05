(ns graphden.packages.type-seed
  "Declared rich types needed before the checker or compiler runs. Composed
   argument bindings are not base-function argument declarations."
  (:require
    [clojure.tools.logging :as log]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records :as records]
    [graphden.types.core :as types]))


(defn- definition-id
  [definition]
  (or (:fn-id definition)
      (records/fn-id (:namespace definition) (:name definition))))


(defn- declared-argument?
  [[_ spec]]
  (and (map? spec) (contains? spec :type)))


(defn- binding-shape-error?
  [definition error]
  (let [{:keys [type arg-name]} (ex-data error)]
    (and (or (:parent definition) (seq (:parents definition)))
         (contains? #{:invalid-arg-spec :invalid-arg-type} type)
         (not (declared-argument? [arg-name (get-in definition [:args arg-name])])))))


(defn- record-declaration!
  [definition]
  (let [id (definition-id definition)
        name (:name definition)]
    (try
      (registry/record-rich-types! id name definition)
      (catch clojure.lang.ExceptionInfo error
        (if (binding-shape-error? definition error)
          ;; Keep explicit types, including malformed ones: a literal binding
          ;; must not hide a real declaration error elsewhere in the same fn.
          ;; Return/effect/lambda declarations remain available without a sweep.
          (registry/record-rich-types!
            id name (update definition :args #(into {} (filter declared-argument?) %)))
          (throw error))))))


(defn seed!
  "Seed declarations, retaining the existing diagnostics for recoverable
   non-callable entries. Return errors by identity so stale registry entries
   cannot conceal a failed registration at the final callable guard."
  [definitions]
  (reduce (fn [errors definition]
            (if-not (:name definition)
              errors
              (try
                (record-declaration! definition)
                errors
                (catch Exception error
                  (log/debug error "Seed-pass record-rich-types! failed for" (:name definition))
                  (assoc errors (definition-id definition) error)))))
          {} definitions))


(defn- declared-return
  [definition]
  (try
    (types/resolve-alias (:return-type definition))
    (catch Exception _
      ;; Alias diagnostics remain the sweep's concern. A failure resolving a
      ;; non-callable alias must not become a new fatal error in this guard.
      ;; An explicit structural callable still requires a successful seed.
      (:return-type definition))))


(defn assert-callable-returns!
  "Before compiling, require every explicitly declared callable return to have
   survived the final seed pass. This is not a type-check certificate; the
   ordinary sweep and its diagnostics remain authoritative."
  [definitions errors]
  (doseq [definition definitions
          :when (and (:name definition) (:return-type definition))
          :let [declared (declared-return definition)]
          :when (types/callable-signature declared)]
    (let [id (definition-id definition)
          failed? (contains? errors id)]
      (when (or failed? (not= declared (:return (registry/rich-type-of-id id))))
        ;; Do not attach the seed exception: its data may contain bound values.
        (throw (ex-info "Declared callable return was not registered before compilation"
                        (merge {:type :packages/callable-seed-missing
                                :fn-id id :fn-name (:name definition)
                                :seed-failed? failed?}
                               (select-keys definition [:source-file :source-line]))))))))
