(ns graphden.tenancy.demo-gc
  "Ephemeral-org reaper (task #7). A demo / trial org carries an `:expires-at`
   (see `graphden.tenancy.org-schema`); once it passes, this HARD-deletes the
   org and every graph row it owns — reclaiming the shared DB so a trial can't
   accumulate forever. A permanent org (`expires-at` NULL — every real tenant
   and the public org) is never selected, so the reaper cannot touch live data.

   Why raw SQL, not the storage protocol: the versioned entities
   (fn / fn-slot / binding / binding-list-item) SOFT-delete through
   `VersionedStorage` — the base row persists for history — which is the
   opposite of what a GC needs. So the reaper deletes the underlying tables
   directly, in one transaction, FK-safely:

     1. version tables first — a version row carries no `org_id` (org-id is an
        IDENTITY-only field, never mirrored onto versions), so each is filtered
        by a subquery on its identity's `org_id`;
     2. identity + the other org-scoped tables by `org_id` — the `:ref-many`
        junction rows (parent-ids etc.) cascade via their
        `owner_id … ON DELETE CASCADE` FK, which is the ONLY real FK in the
        graph schema (plain `:ref` columns are unconstrained UUIDs, so nothing
        else cascades and order among the identity tables is irrelevant);
     3. the `:org` registry row itself.

   Scope: this purges the tenant's GRAPH data (`default-scoped-entities`) + the
   org row. A demo org's platform rows (`:user` / `:token` / `:grant`) are left
   — an orphaned token is inert (it authenticates to an org that no longer
   exists; `tenancy.deploy` already treats a token for a missing org as
   unusable), so this is harmless row-debt, a tidy-up follow-up, not a hole.

   Wired as `:tenancy/demo-gc` (addon only): a `ScheduledExecutorService` runs
   `sweep!` on a fixed period, mirroring `:exec/cleanup-scheduler`."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.tenancy.storage :as ts]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (java.time
      Instant
      OffsetDateTime
      ZoneOffset)
    (java.util.concurrent
      Executors
      ScheduledExecutorService
      TimeUnit)))


;; Versioned identity entity → the column on its `<identity>_version` table that
;; FKs back to the identity row. Three follow the `<identity>_id` pattern
;; (fn_version.fn_id, fn_slot_version.fn_slot_id, binding_version.binding_id),
;; but binding-list-item-version's id-field is the SHORTENED `item_id` (see
;; `graphden.schema.versioned.schema`), so the map is explicit, not derived.
(def ^:private version-fk-column
  {:fn "fn_id"
   :fn-slot "fn_slot_id"
   :binding "binding_id"
   :binding-list-item "item_id"})


(defn- table
  "SQL table name for an entity keyword — `:fn-slot` → \"fn_slot\". Mirrors the
   DDL's `kw->snake-case` for the simple kebab entity names the schema uses
   (validated against the real tables by `demo-gc-test`)."
  [entity]
  (str/replace (name entity) "-" "_"))


(defn expired-org-names
  "Names of orgs whose `:expires-at` is set and has passed `now`. A NULL
   `expires-at` (a permanent org) is never returned."
  [ds ^Instant now]
  ;; Bind an OffsetDateTime, not a bare Instant: this raw query bypasses the
  ;; storage codec, and pgjdbc can't infer the SQL type for a java.time.Instant.
  (mapv :name
        (jdbc/execute! ds
                       ["SELECT name FROM org WHERE expires_at IS NOT NULL AND expires_at < ?"
                        (OffsetDateTime/ofInstant now ZoneOffset/UTC)]
                       {:builder-fn rs/as-unqualified-lower-maps})))


(defn purge-org!
  "HARD-delete every graph row owned by `org-name` + its `:org` registry row,
   in one transaction. Idempotent — a name with no rows deletes nothing. See
   the ns docstring for the FK-safe ordering rationale. `org-name` is bound as
   a `?` parameter; the table names come from a hardcoded entity list, so there
   is no injection surface."
  [ds org-name]
  (jdbc/with-transaction [tx ds]
                         ;; 1. version rows — no org_id column, filter by identity subquery.
                         (doseq [[ident fk-col] version-fk-column]
                           (jdbc/execute! tx
                                          [(str "DELETE FROM " (table (keyword (str (name ident) "-version")))
                                                " WHERE " fk-col
                                                " IN (SELECT id FROM " (table ident) " WHERE org_id = ?)")
                                           org-name]))
                         ;; 2. identity + other org-scoped tables (junction rows cascade from owner).
                         (doseq [entity ts/default-scoped-entities]
                           (jdbc/execute! tx [(str "DELETE FROM " (table entity) " WHERE org_id = ?") org-name]))
                         ;; 3. the org registry row.
                         (jdbc/execute! tx ["DELETE FROM org WHERE name = ?" org-name])))


(defn sweep!
  "One reaping pass: purge every expired org. Per-org try/catch so one failing
   org can't abort the rest. Returns the vector of purged org names."
  ([ds] (sweep! ds (Instant/now)))
  ([ds ^Instant now]
   (let [names (expired-org-names ds now)]
     (doseq [nm names]
       (try
         (purge-org! ds nm)
         (log/info "demo-gc: purged expired org" nm)
         (catch Exception e
           (log/warn e "demo-gc: purge failed for org" nm))))
     names)))


;; =============================================================================
;; Integrant component — periodic reaper (addon only).
;; =============================================================================

(defn start-reaper!
  "Spawn a scheduled tick that runs `sweep!` every `period-ms`, swallowing
   Exceptions (not Errors) so a transient failure doesn't kill the scheduler.
   Returns the `ScheduledExecutorService`."
  ^ScheduledExecutorService [ds period-ms]
  (let [scheduler (Executors/newSingleThreadScheduledExecutor)]
    (log/info "Starting demo-org reaper — period" period-ms "ms")
    (ScheduledExecutorService/.scheduleAtFixedRate
      scheduler
      ^Runnable (fn []
                  (try
                    (sweep! ds)
                    (catch Exception e
                      (log/warn e "demo-gc sweep failed"))))
      period-ms period-ms
      TimeUnit/MILLISECONDS)
    scheduler))


(defn stop-reaper!
  "Shut a running reaper's scheduler down."
  [^ScheduledExecutorService scheduler]
  (when scheduler
    (log/info "Stopping demo-org reaper...")
    (ScheduledExecutorService/.shutdownNow scheduler)))
