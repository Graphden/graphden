(ns graphden.packages.storage.pg.impls
  "Implementations for storage/pg base functions.

   Each `defbase` body delegates to the helper namespace under
   `src/graphden/storage/sql/pg.clj`, passing the implicit `ctx`
   symbol through as an explicit argument. Heavy logic — HoneySQL
   format, datasource resolution, error handling — lives there so
   each base-fn impl stays a thin primitive."
  (:require
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.postgres.codec :as codec]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.sql.pg :as pg]
    [graphden.versioning.storage.core :as vs]))


(defbase pg-query
  [hsql]
  ;; `:raw-sql` (on top of `:db`) marks this as the raw escape hatch a
  ;; cloud/tenant graph must NOT reach — it runs arbitrary HoneySQL
  ;; against the platform pool, bypassing the org-scoped + RLS storage
  ;; protocol. `cloud-forbidden-effects` blocks `:raw-sql`; the safe
  ;; `:query-entities` path records only `:db`.
  (cr/record-effect! :db)
  (cr/record-effect! :raw-sql)
  (pg/pg-query ctx hsql))


(defbase pg-execute
  [hsql]
  (cr/record-effect! :db)
  (cr/record-effect! :raw-sql)
  (pg/pg-execute ctx hsql))


(defbase pg-tx
  [body]
  (cr/record-effect! :db)
  (cr/record-effect! :raw-sql)
  (pg/pg-tx ctx body))


(defbase decode-row
  "Decode one raw next.jdbc result row into graphden's canonical
   entity shape: snake_case column keys → kebab-case kw, enum strings
   → keywords, jsonb PGobjects → Clojure data, timestamptz columns →
   `Instant`. The field-specs are fetched via the storage protocol's
   `current-fields` introspection — no postgres-storage internals leak
   into the graph layer.

   Single-row only; graph users compose with `:map` for batches. The
   field-specs lookup hits the metadata cache (O(1) after first read),
   so per-row overhead is bounded by the codec's per-field decode."
  [row entity-type]
  (let [storage (request/require-storage ctx)
        field-specs (sp/current-fields storage (keyword entity-type))]
    (codec/row->entity row field-specs)))


(defbase pg-notify
  "Emit a Postgres NOTIFY event on the `graphden_events` channel so
   sibling pods react to a write. `event` is a map (e.g.
   `{:kind :fn :op :invalidate :id \"uuid-...\"}` for fn-graph
   invalidation, `{:kind :service :op :write :id \"uuid-...\"}` for
   service reconciler reacts). Cross-pod payload semantics live in
   the LISTEN callback registrations in `system/core.clj`.

   Best-effort: a NOTIFY failure is logged but doesn't throw —
   sibling pods will catch up at the next mutation. Single library
   call over `(:notify-emitter ctx)`; falls through to a no-op when
   the ctx has no emitter wired (tests without PG)."
  [event]
  (cr/record-effect! :db)
  (when-let [emit (:notify-emitter ctx)]
    (emit event))
  nil)


(defbase storage-query-identities
  "Run `sp/query-entities` against the UNWRAPPED base storage —
   bypasses VersionedStorage's version-resolution layer. Returns
   the raw identity rows (codec-decoded, ref-many fields populated
   from junction tables).

   Use this for loading identity rows of versioned entities (`:fn`,
   `:fn-slot`, `:binding`, `:binding-list-item`) ahead of in-graph
   version resolution via `:resolve-versioned-rows`. For non-versioned
   entities `:pg-query` + `:decode-row` is sufficient (and more
   explicit about the SQL); use this primitive only when ref-many
   junction population matters.

   `where` is a HoneySQL-style equality-map `{column value}` or
   `{column [val1 val2 …]}` (IN-clause). nil/empty `where` returns
   every row of the entity table (full scan).

   Single library call (`graphden.storage.protocol.core/query-entities`
   against the base storage); §3.1 thin wrapper, no composition logic."
  [entity-type where]
  (cr/record-effect! :db)
  (let [base (vs/unwrap (request/require-storage ctx))]
    (vec (sp/query-entities base (keyword entity-type) (or where {})))))


(defbase invalidate-graph-cache
  "Drop derived caches on the executor context. Two modes:

   - `seeds = nil` / empty → full clear. Use when the caller doesn't
     know which fns the mutation affects (cross-cutting changes,
     schema migrations).

   - `seeds = [<fn-uuid> …]` → delta invalidate. Just the named
     fn-ids plus their transitive dependents get recompiled; the
     rest of the registry stays warm.

   Single library call over `executor.context/invalidate-graph-cache!`
   — admin can vary the seed set per write site without touching
   Clojure (e.g. emit narrower seeds for a binding-edit, broader
   seeds for a slot rename). §3.1 thin wrapper."
  [seeds]
  (cr/record-effect! :db)
  (let [invalidate! (requiring-resolve 'graphden.executor.context/invalidate-graph-cache!)]
    (if (seq seeds)
      (invalidate! ctx (set seeds))
      (invalidate! ctx)))
  nil)


(defbase invalidate-after-write
  "Entity-type-aware cache invalidation. Derives the affected-fn-ids
   seed set from `entity-type` + `entity-data` and invalidates via
   the same path as `:invalidate-graph-cache`. The seed-computation
   knows that `:fn` writes seed themselves, `:fn-slot` / `:binding`
   seed their owner fn-id, `:binding-list-item` seeds the parent
   binding's fn-id (one extra read), and other types fall through
   to a full clear.

   Use this in write-site graph composition (delete-apply, create-
   apply, update-apply) where the seed shape is a pure function of
   the entity-type + the row's FKs. §3.1: single library call into
   `crud.entities/invalidate!` which already encapsulates the seed
   case-dispatch."
  [entity-type entity-data]
  (cr/record-effect! :db)
  (let [invalidate! (requiring-resolve 'graphden.crud.entities/invalidate!)
        storage (request/require-storage ctx)]
    (invalidate! ctx storage (keyword entity-type) (or entity-data {})))
  nil)


(defbase notify-after-write
  "Emit `pg_notify` events that describe the just-completed write,
   so sibling pods invalidate their compiled-registry slice on the
   same fn-ids. Service writes get `{:kind :service :op <op> :id}`;
   fn-graph entity writes (`:fn` / `:fn-slot` / `:binding` /
   `:binding-list-item`) get one `{:kind :fn :op :invalidate :id <seed>}`
   per affected seed (or one empty-id event for the full-clear case).

   `op` is the CRUD operation kind — `:write` or `:delete`. Service
   reconciler keys off this; fn-graph emissions always carry
   `:op :invalidate` regardless.

   §3.1 single library call into `crud.entities/notify-after-write!`."
  [entity-type op entity-data]
  (cr/record-effect! :db)
  (let [notify! (requiring-resolve 'graphden.crud.entities/notify-after-write!)
        storage (request/require-storage ctx)]
    (notify! ctx storage (keyword entity-type) (keyword op) (or entity-data {})))
  nil)


(def impls
  {:pg-query pg-query
   :pg-execute pg-execute
   :pg-tx pg-tx
   :decode-row decode-row
   :pg-notify pg-notify
   :storage-query-identities storage-query-identities
   :invalidate-graph-cache invalidate-graph-cache
   :invalidate-after-write invalidate-after-write
   :notify-after-write notify-after-write})
