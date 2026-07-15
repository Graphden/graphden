(ns graphden.test-infra.shared-bootstrap
  "JVM-wide one-shot bootstrap into a 'golden' PostgreSQL template DB.

   Why this exists
   ===============
   `bootstrap-from-packages!` is heavy (~10–14 s with type-check off):
   schema migration, primitive upsert, base-fn registration,
   declarative fn-defs sync writing ~700 rows. Done in every
   integration NS, the cost is N × 14 s — that's where the bulk of
   `bb test :integration` wall time was sitting.

   The bootstrap is also *pure on its inputs*: identical
   `packages` produces a byte-identical schema + the same fn-def
   rows. So we run it ONCE per JVM into a golden DB, then clone
   per-NS DBs with `CREATE DATABASE … TEMPLATE \"<golden>\"` — a
   ~100 ms filesystem copy. Combined with the process-wide
   compile-all LRU, the per-NS bootstrap drops to ~1 s.

   The in-memory state (`*default-registry*` base-fn impls, rich
   types) is process-global, so registering once during the golden
   bootstrap makes the impls visible to every later
   `cr/rebuild!` / `cr/execute` call.

   Per-package-set
   ===============
   `ensure-golden!` caches one entry per `(vec packages)`. NSes that
   bootstrap a different package set just get a different golden +
   their own per-NS clones. The `test_golden_<hash>` DBs live for
   the JVM lifetime; they're cleaned up by the shared-container
   kaocha post-run hook (via `drop-all-golden-databases!`)."
  (:require
    [clojure.tools.logging :as log]
    [graphden.executor.registry :as registry]
    [graphden.executor.registry.core :as registry-core]
    [graphden.packages.records :as records]
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.packages.schema :as pkgs]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.services.schema :as svcs]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.core :as sys]
    [graphden.test-infra.shared-container :as sc]
    [graphden.versioning.storage.core :as vs]
    [next.jdbc :as jdbc])
  (:import
    (java.sql
      SQLException)))


(def ^:private golden-state
  "{pkg-vec → {:db-name string, :bootstrap bootstrap-info-map}}.
   First successful `ensure-golden!` for a package set populates
   this; sibling callers read directly."
  (atom {}))


(def ^:private lock (Object.))


(defn- full-schema
  "Same schema combination `test_setup/full-schema` uses; duplicated
   here to keep this ns free of the `executor.test-setup` import
   cycle (`test_setup` consumes our public API)."
  []
  (-> (mds/create-builder)
      (gds/extend-builder)
      (vts/extend-builder)
      (vds/extend-builder)
      (es/extend-builder)
      (svcs/extend-builder)
      (pkgs/extend-builder)
      (ds/build)))


(defn- exec-on-cluster!
  "Run `sql` against the cluster bootstrap connection. Returns true on
   success, false when the SQLException matches an ignored state
   (caller treats e.g. duplicate-database as no-op)."
  [sql ignore-sqlstates]
  (let [{:keys [jdbc-url username password]} (sc/base-cluster-config)]
    (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                           :user username
                                           :password password})]
      (try
        (jdbc/execute! conn [sql])
        true
        (catch SQLException e
          (if (contains? (set ignore-sqlstates)
                         (SQLException/.getSQLState e))
            false
            (do (log/warn e "Cluster SQL failed:" sql)
                (throw e))))))))


(defn- bootstrap-into-golden!
  "Open a storage handle to `golden-db-name`, run the full
   schema + primitives + base-fns + fn-defs sync, then close.
   Returns the bootstrap-info map (`:ns-id-map :all-name->id
   :fn-rows …`) that every NS clone shares.

   The `binding registry/*registry-override* nil` forces base-fn
   registrations into the process-global default registry. Without
   that, a `with-clean-registry`-scoped caller's thread-local
   override receives the writes; the writes evaporate when the
   override unbinds, leaving sibling NSes (which see the cached
   golden entry and skip the bootstrap) without any base-fn impls."
  [packages golden-db-name]
  (log/info "Bootstrapping golden test DB"
            {:db golden-db-name :packages packages})
  (let [config (update (sc/base-cluster-config)
                       :jdbc-url sc/jdbc-url-with-database golden-db-name)
        storage (pg/create-storage config)]
    (try
      (sp/initialize storage (full-schema))
      (sp/upsert-entities storage :fn
                          (mapv #(dissoc % :kind)
                                (records/boot-primitive-records)))
      (let [versioned (vs/wrap-with-versioning storage "main")]
        (binding [registry/*registry-override* nil]
          (sys/bootstrap-from-packages! versioned packages
                                        {:skip-type-check? true})))
      (finally
        (sp/close storage)))))


(defn ensure-golden!
  "Idempotent: ensure a 'golden' DB on the cluster, fully bootstrapped
   with `packages`. Returns `{:db-name :bootstrap}`.

   First caller per JVM × package-set pays the ~14 s bootstrap;
   subsequent callers return the cached entry."
  [packages]
  (let [k (vec packages)]
    (or (get @golden-state k)
        (locking lock
          (or (get @golden-state k)
              (let [db-name (str "test_golden_" (Math/abs (hash k)))]
                (exec-on-cluster! (str "CREATE DATABASE \"" db-name "\"")
                                  #{"42P04"})
                (let [info (bootstrap-into-golden! packages db-name)
                      entry {:db-name db-name :bootstrap info}]
                  (swap! golden-state assoc k entry)
                  entry)))))))


(defn ensure-ns-database-from-golden!
  "Ensure a per-NS DB cloned from the golden. Returns
   `{:db-config :bootstrap}`:
     - `:db-config` → `{:jdbc-url :username :password :pool-size}`
       pointing at the per-NS DB.
     - `:bootstrap` → cached info from the one-shot golden bootstrap;
       every NS sees the same `:fn-rows` / `:ns-id-map` / etc."
  [ns-ident packages]
  (let [{:keys [db-name bootstrap]} (ensure-golden! packages)
        ns-config (sc/ensure-ns-database! ns-ident db-name)]
    {:db-config ns-config
     :bootstrap bootstrap}))


(def ^:private swept-state
  "{pkg-vec → rich-types-map}. The topological type-check sweep inside
   `bootstrap-from-packages!` is the single most expensive fixture step
   (~40 s vs ~14 s for the storage-sync + seed passes), and — like the
   golden bootstrap — it is *pure on its inputs*: the same package set
   yields the same computed rich-types. So a full-system NS need not
   re-run it; we run it ONCE per JVM × package-set and cache the
   resulting map here."
  (atom {}))


(defn ensure-swept-rich-types!
  "Idempotent: return the rich-types map a full type-check sweep of
   `packages` produces. First caller per JVM × package-set pays the
   ~40 s sweep on a throwaway golden clone; the rest read the cache.

   The map is a snapshot of `*rich-types-override*` AFTER the sweep —
   the global-registry seed PLUS every composed fn-def's computed
   return / effects. Seed a fresh isolated override with it (see
   `bootstrap-with-cached-sweep!`) and the compile that follows sees
   the identical types running the sweep inline would have produced.

   Sweeping in a bound `*rich-types-override*` keeps the capture off
   the process-global registry, so it can't leak into a sibling NS."
  [packages]
  (let [k (vec packages)]
    (or (get @swept-state k)
        (locking lock
          (or (get @swept-state k)
              (let [{:keys [db-config]} (ensure-ns-database-from-golden!
                                          "swept-rich-types-capture" packages)
                    storage (pg/create-storage db-config)]
                (try
                  (let [versioned (vs/wrap-with-versioning storage "main")
                        captured (binding [registry-core/*rich-types-override*
                                           (atom (registry-core/snapshot-for-isolation))]
                                   ;; Golden clone is already synced; this re-sync is
                                   ;; idempotent — we run it only to reach the sweep,
                                   ;; which populates the bound override.
                                   (sys/bootstrap-from-packages! versioned packages
                                                                 {:skip-type-check? false})
                                   @registry-core/*rich-types-override*)]
                    (swap! swept-state assoc k captured)
                    captured)
                  (finally
                    (sp/close storage)))))))))


(defn bootstrap-with-cached-sweep!
  "Drop-in for `(bootstrap-from-packages! storage packages
   {:skip-type-check? false})` inside a `with-isolated-rich-types`
   fixture: runs the storage-sync + seed passes but SKIPS the ~40 s
   topological sweep, then overwrites the ambient isolated
   `*rich-types-override*` with the cached swept snapshot for
   `packages` (`ensure-swept-rich-types!`). The compile that follows
   sees the same computed types the inline sweep would have produced,
   for ~40 s less per NS after the first.

   MUST run inside a bound `*rich-types-override*` (the isolation
   fixture) — it `reset!`s that atom, and would otherwise clobber the
   process-global registry."
  [storage packages]
  (assert (some? registry-core/*rich-types-override*)
          "bootstrap-with-cached-sweep! must run inside with-isolated-rich-types")
  (sys/bootstrap-from-packages! storage packages {:skip-type-check? true})
  (reset! registry-core/*rich-types-override* (ensure-swept-rich-types! packages))
  nil)


(defn drop-all-golden-databases!
  "Drop every golden DB the JVM minted. Called from the kaocha
   post-run hook BEFORE the cluster shutdown."
  []
  (let [dbs (set (map :db-name (vals @golden-state)))]
    (doseq [db dbs]
      (try
        (exec-on-cluster! (str "DROP DATABASE IF EXISTS \"" db
                               "\" WITH (FORCE)")
                          #{})
        (catch Exception e
          (log/warn e "Failed to drop golden test DB" db))))
    (reset! golden-state {})))
