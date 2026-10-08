(ns graphden.storage.graph-writer
  "Transaction-scoped serialization of semantic graph changes.

   Installed storage decorators explicitly supply trusted private-org scope.
   Public, raw and unknown writers take the exclusive global lock. A private
   writer takes that same lock shared, then its org lock exclusive. Execution,
   queue and service traffic does not participate."
  (:require
    [clojure.string :as str]
    [graphden.storage.tx :as tx]
    [next.jdbc :as jdbc]))


(defprotocol GraphWriterScope

  (writer-scope
    [storage]
    "Trusted private-org id, or nil for a public writer."))


(defprotocol GraphWriteAuthorization

  (authorize-graph-write!
    [storage entity-name data id]
    "Read-only equivalent of the decorated write authorization. Throws on
     denied ownership, namespace or branch policy; never mutates rows."))


(defprotocol GraphCreationAuthorization

  (authorize-graph-creation!
    [storage fn-data branch-id]
    "Authorize a proposed fn identity before it exists: target namespace,
     branch policy and identity references. Actual creates remain decorated."))


(defn assert-creation-authorized!
  [storage fn-data branch-id]
  (if (satisfies? GraphCreationAuthorization storage)
    (authorize-graph-creation! storage fn-data branch-id)
    (throw (ex-info "This storage cannot authorize graph creation"
                    {:type :authz/forbidden}))))


(def ^:private semantic-entities
  #{:fn :fn-slot :slot :binding :binding-list-item
    :fn-version :fn-slot-version :binding-version :binding-list-item-version
    :ns :branch :branch-merge :branch-approval
    :resource-override :resource-override-version :package-install
    :grant :role :user :org})


(def global-lock-key "graphden|semantic-writer")


(defn org-lock-key
  "Private writer lock key, namespaced separately from identity locks."
  [org-id]
  (str global-lock-key "|org|" org-id))


(defn assert-write-authorized!
  "Ask an explicitly supported storage stack to authorize without writing.
   Unknown decorators cannot advertise a writable preview."
  [storage entity-name data id]
  (if (satisfies? GraphWriteAuthorization storage)
    (authorize-graph-write! storage entity-name data id)
    (throw (ex-info "This storage cannot authorize a graph-write preview"
                    {:type :authz/forbidden}))))


(defn- trusted-scope
  [storage]
  (when (satisfies? GraphWriterScope storage)
    (let [scope (writer-scope storage)]
      (when (and (string? scope) (not (str/blank? scope)) (not= "public" scope))
        scope))))


(defn- delegated-scope
  [storage context]
  (when-let [token (tx/writer-context storage)]
    (when-not (and (identical? context token)
                   @(:active? token)
                   (identical? (tx/datasource storage) (:connection token)))
      (throw (ex-info "Graph writer context is closed or belongs to another connection"
                      {:type :graph-write/invalid-context})))
    @(:writer-scope token)))


(defn- acquire-locks!
  [connection scope]
  ;; PG built-in RPC: parameterized SQL, before branch/row/identity locks.
  (jdbc/execute! connection
                 [(if (= :global scope)
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"
                    "SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))")
                  global-lock-key])
  (when-not (= :global scope)
    (jdbc/execute! connection
                   ["SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"
                    (org-lock-key scope)])))


(defn- enter-writer!
  [storage]
  (let [context tx/*transaction-context*
        _ (when-not (and context @(:active? context))
            (throw (ex-info "Graph writer context is inactive"
                            {:type :graph-write/invalid-context})))
        delegated (delegated-scope storage context)
        requested (if (and (:owned? context)
                           (satisfies? GraphWriterScope storage))
                    (or (trusted-scope storage) :global)
                    (or delegated :global))
        held @(:writer-scope context)]
    (when (and held (not= :global held) (not= held requested))
      (throw (ex-info "Graph writer scope cannot change inside a transaction"
                      {:type :graph-write/scope-change})))
    (when-not held
      (acquire-locks! (:connection context) requested)
      (reset! (:writer-scope context) requested))
    (tx/with-writer-context storage)))


(defn call-with-write
  "Run `f` on the fully decorated transaction-bound storage, holding the
   semantic writer lock until the OUTER transaction commits or rolls back.
   Reentrant physical writes join only the current internal context token.
   No pooled backend means no concurrency or atomicity promise."
  [storage entity-name f]
  (if (and (or (= :graph entity-name) (contains? semantic-entities entity-name))
           (tx/datasource storage))
    (do
      ;; Reject stale/foreign tokens before borrowing another connection.
      (delegated-scope storage tx/*transaction-context*)
      (tx/in-transaction storage #(f (enter-writer! %))))
    (f storage)))


(defmacro with-write
  "Bind the named storage to its guarded transaction copy for `body`."
  [[storage entity-name] & body]
  `(call-with-write ~storage ~entity-name (fn [~storage] ~@body)))
