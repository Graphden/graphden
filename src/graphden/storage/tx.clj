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
    [next.jdbc.transaction :as jdbc-tx]))


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
  (or (map-backend storage #(assoc % :pool conn)) storage))


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
    (binding [jdbc-tx/*nested-tx* :ignore]
      (jdbc/with-transaction [tx ds]
                             (f (with-connection storage tx))))
    (f storage)))
