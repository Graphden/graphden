(ns graphden.storage.tx
  "Transactions through a decorated storage stack.

   A pooled backend (`PostgresStorage`) carries its connectable as `:pool`;
   a decorator carries the storage it wraps as `:base` (the tenancy addon's
   `OrgScopedStorage`) or `:base-storage` (`VersionedStorage`). Code that
   needs a transaction around several storage calls must

   1. find the pool — which, on the cloud stack `Versioned(OrgScoped(Postgres))`,
      is NOT on the storage it holds but one layer further down, and
   2. run those calls on a copy of the WHOLE stack whose backend talks to the
      transaction connection — so every write still passes each decorator
      (the org stamp, the per-namespace write guard, the quota) instead of
      skipping straight to the backend.

   `datasource` and `with-connection` do exactly that, walking the
   `:pool` / `:base` / `:base-storage` convention (the same one
   `storage.sql.pg` resolves its datasource by), so a decorator needs no
   code of its own — only to keep its inner storage under one of those.

   Row-level security holds inside the transaction for free: the tenancy
   pool sets the org on every connection it hands out, and
   `with-transaction` borrows the transaction's connection from that pool on
   the request thread."
  (:require
    [next.jdbc :as jdbc]
    [next.jdbc.transaction :as jdbc-tx])
  (:import
    (java.sql
      Connection)))


(def ^:dynamic *transaction-context*
  "Internal root-transaction lifetime. Nested storage transactions share its
   physical connection and writer scope; it becomes inactive after commit or
   rollback. Never populated from request data or storage map fields."
  nil)


(defn- map-backend
  "Apply `f` to the backend (the map that holds `:pool`) at the bottom of
   `storage`'s decorator chain, rebuilding every layer above it. nil when
   the chain has no pooled backend."
  [storage f]
  (cond
    (not (map? storage)) nil
    (contains? storage :pool) (f storage)
    (:base storage) (some->> (map-backend (:base storage) f) (assoc storage :base))
    (:base-storage storage) (some->> (map-backend (:base-storage storage) f)
                                     (assoc storage :base-storage))
    :else nil))


(defn datasource
  "The pool (or, inside `in-transaction`, the transaction connection) at the
   bottom of `storage`'s decorator chain; nil for a storage with no pooled
   backend (an in-memory test double)."
  [storage]
  (cond
    (not (map? storage)) nil
    (contains? storage :pool) (:pool storage)
    :else (some-> (or (:base storage) (:base-storage storage)) datasource)))


(defn with-connection
  "`storage` with its backend bound to `conn` — every layer above the backend
   kept, so writes still run through each decorator. `storage` unchanged when
   it has no pooled backend."
  [storage conn]
  (or (map-backend
        storage
        (fn [backend]
          (let [context (::writer-context (meta backend))]
            (cond-> (assoc backend :pool conn)
              (and context (not (identical? conn (:connection context))))
              (vary-meta dissoc ::writer-context)))))
      storage))


(defn writer-context
  "Internal delegation token on a transaction-bound backend, if any. A
   consumer must verify context and connection identity and active lifetime."
  [storage]
  (cond
    (not (map? storage)) nil
    (contains? storage :pool) (::writer-context (meta storage))
    :else (some-> (or (:base storage) (:base-storage storage)) writer-context)))


(defn with-writer-context
  "Mark physical delegation through the current guarded storage stack. This
   token only permits joining its lock; all storage decorators stay present."
  [storage]
  (or (map-backend storage #(vary-meta % assoc ::writer-context *transaction-context*))
      storage))


(defn external-transaction?
  "Whether storage already holds a JDBC transaction whose commit is owned by
   a caller outside `in-transaction`."
  [storage]
  (let [ds (datasource storage)]
    (and (instance? Connection ds) (not (.getAutoCommit ^Connection ds)))))


(defn assert-owns-commit!
  "Require a pooled root operation with a known commit boundary. Compound
   graph operations publish only after their own transaction has committed."
  [storage]
  (when (or *transaction-context* (external-transaction? storage)
            (nil? (datasource storage)))
    (throw (ex-info "Compound graph writes require their own database transaction"
                    {:type :graph-write/commit-boundary-required}))))


(defn without-connection
  "`storage` with its backend's connectable elided — a value that is equal
   for a storage and for every transaction copy of it (`with-connection`),
   and distinct between two real storages. For caches keyed by storage."
  [storage]
  (or (map-backend storage #(dissoc % :pool)) storage))


(defn in-transaction
  "Run `(f tx-storage)` in ONE database transaction, `tx-storage` being
   `storage` bound to the transaction connection (`with-connection`). Nested
   `with-transaction`s inside run INLINE (`:ignore`) — a nested commit would
   end this transaction early and release any `pg_advisory_xact_lock` taken
   in it before the check-then-write it protects finished. A storage with no
   pooled backend runs `(f storage)` as is."
  [storage f]
  (if-let [ds (datasource storage)]
    (if (and *transaction-context*
             (identical? ds (:connection *transaction-context*)))
      (f storage)
      (do
        (when (and *transaction-context*
                   (some? @(:writer-scope *transaction-context*)))
          (throw (ex-info "A graph writer cannot open another transaction"
                          {:type :graph-write/transaction-mismatch})))
        (let [context (volatile! nil)
              owned? (not (external-transaction? storage))]
          (try
            (binding [jdbc-tx/*nested-tx* :ignore]
              (jdbc/with-transaction [conn ds]
                                     (let [root {:connection conn :active? (atom true)
                                                 :owned? owned? :writer-scope (atom nil)}]
                                       (vreset! context root)
                                       (binding [*transaction-context* root]
                                         (f (with-connection storage conn))))))
            (finally
              (when-let [root @context]
                (reset! (:active? root) false)))))))
    (f storage)))
