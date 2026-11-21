(ns graphden.input.authority
  "Request-local structured storage operations. A decorated tenant store must
   implement every operation. Generic CRUD never establishes this authority."
  (:require
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenant]))


(def ^:dynamic ^:private *operation* nil)


(defn call
  [storage branch facts f]
  (let [active? (volatile! true)]
    (binding [*operation* {:storage storage :org (tenant/current-org)
                           :principal tenant/*current-principal* :branch branch :facts facts
                           :thread (Thread/currentThread) :active? active?}]
      (try (f) (finally (vreset! active? false))))))


(defn current
  "The original decorated transaction, subject and organization must agree."
  [storage]
  (let [original (:storage *operation*)
        context tx/*transaction-context*]
    (when (and *operation* (identical? (Thread/currentThread) (:thread *operation*)) @(:active? *operation*)
               (or (identical? storage original)
                   (and (::store original) (identical? (::store original) (::store storage))
                        context @(:active? context)
                        (identical? (:connection context) (tx/datasource original))
                        (identical? (:connection context) (tx/datasource storage))))
               (= (tenant/current-org) (:org *operation*))
               (= tenant/*current-principal* (:principal *operation*)))
      (assoc (:facts *operation*) :branch (:branch *operation*)))))


(defn operations
  [storage]
  (let [ops (::operations storage)]
    (cond
      (and (map? ops) (every? #(fn? (get ops %)) [:source-bindings :version-ids :validate-candidates!])) ops
      (or ops (tenant/tenancy-addon-active?) (satisfies? writer/GraphWriterScope storage))
      (throw (ex-info "Structured input storage is unavailable" {:type :authz/forbidden}))
      :else nil)))
