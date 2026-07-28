(ns graphden.tenancy.plan
  "Per-org PLAN / tier (task #4 + #7 row-cap). A tenant org carries a `:plan`
   slug (nil = the locked free tier); a plan resolves to BOTH the effect
   allow-list its submitted graph runs under AND its quota ceilings (currently
   `:max-fns`, the row-cap). It is the config backbone the tier work hangs off —
   the 'couple of buttons' upgrade is a change to the org's `:plan` row.

   Installed by the addon:
   - effects → `compile-runtime/cloud-allowed-effects-resolver`; `crud.fn-execution`
     reads it per execute to set the exec ctx's `:allowed-effects`.
   - quota → `tenancy.storage/entity-quota-exceeded?`; OrgScopedStorage reads it
     per `:fn` / `:binding-list-item` create to reject a tenant over a ceiling.
   Both only bind for a real tenant org — the platform / public ctx is
   unrestricted and uncapped."
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.storage :as ts]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


(def plans
  "Plan slug → `{:effects <allow-list> :max-fns <n|nil> :max-list-items <n|nil>}`.
   `free` is the locked default (`#{:db :state :time :random}`); `network`
   additionally allows outbound `:network` (external HTTP / SQL, itself guarded
   by the egress broker — #5) and lifts the ceilings. A nil ceiling = uncapped.

   TWO ceilings, because a tenant controls TWO independent DB-growth vectors:
   `:max-fns` bounds `:fn` rows (slots / bindings scale with fns, so fn-count is
   their proxy), and `:max-list-items` bounds `:binding-list-item` rows — SEQUENCE
   content, appended one row (+ one version row) per item, so NOT bounded by fn
   count. Without the second ceiling a tenant balloons the shared DB with one fn
   + an unbounded list. Extend here as tiers are added (e.g. a #6 service tier)."
  {"free"    {:effects cr/default-cloud-allowed-effects
              :max-fns 500
              :max-list-items 50000}
   "network" {:effects (conj cr/default-cloud-allowed-effects :network)
              :max-fns 5000
              :max-list-items 500000}})


(def ^:private default-plan
  "The plan a real tenant with an unknown / nil `:plan` slug falls back to — the
   locked free tier. The PUBLIC org never resolves a plan (it is uncapped +
   unrestricted); only real tenant orgs reach here."
  (get plans "free"))


(defn- tenant-plan
  "The plan map for a REAL tenant `org` (nil for the public org / no org), read
   from its `:plan` slug via the platform `storage` (`:org` is tenant-forbidden,
   so this runs in the platform ctx on the tenant's behalf). An unknown / nil
   slug on a real tenant → the free-tier default."
  [storage org]
  (when (and org (not= org tc/public-org))
    (let [slug (:plan (first (sp/query-entities storage :org {:name org})))]
      (get plans slug default-plan))))


(defn allowed-effects-for
  "The effect allow-list for tenant `org`. Public org / no org → the locked free
   effects."
  [storage org]
  (or (:effects (tenant-plan storage org))
      cr/default-cloud-allowed-effects))


;; The gated GROWTH entities → the `{table, plan-ceiling-key}` they're measured
;; against. Table names come from this hardcoded map (no injection surface).
(def ^:private quota-spec
  {:fn                {:table "fn" :ceiling :max-fns}
   :binding-list-item {:table "binding_list_item" :ceiling :max-list-items}})


(defn entity-count
  "How many `entity` rows tenant `org` owns — a raw `count(*)`, no row load.
   `storage` must be (wrap) a PostgresStorage exposing `:pool`; when it doesn't
   (misconfig), returns nil so the caller fails OPEN (a broken count must never
   block a tenant's write). nil for an entity outside `quota-spec`."
  [storage org entity]
  (when-let [table (:table (get quota-spec entity))]
    (when-let [ds (:pool storage)]
      (-> (jdbc/execute-one! ds
                             [(str "SELECT count(*) AS n FROM \"" table "\" WHERE org_id = ?") org]
                             {:builder-fn rs/as-unqualified-lower-maps})
          :n))))


(defn fn-count
  "Convenience: `:fn` row count for `org` (see `entity-count`)."
  [storage org]
  (entity-count storage org :fn))


(defn over-entity-quota?
  "True when tenant `org` is at/over its plan ceiling for a GATED `entity`
   (`:fn` → `:max-fns`, `:binding-list-item` → `:max-list-items`). Uncapped plan
   / public org / ungated entity / uncountable storage → false (never over)."
  [storage org entity]
  (boolean
    (when-let [ceiling-key (:ceiling (get quota-spec entity))]
      (when-let [cap (get (tenant-plan storage org) ceiling-key)]
        (when-let [n (entity-count storage org entity)]
          (>= n cap))))))


(defn quota-status
  "Current usage vs plan ceilings for a REAL tenant `org`, for the editor's
   proactive display: `{:plan <slug> :fns {:used n :max m|nil} :list-items
   {:used n :max m|nil}}` (a nil `:max` = uncapped on that axis). nil for the
   public org / no org (uncapped). Reads the tenant-forbidden `:org` row + raw
   `count(*)`s via the platform `storage`, so it runs in the platform ctx on the
   tenant's behalf — same seam contract as `over-entity-quota?`."
  [storage org]
  (when (and org (not= org tc/public-org))
    (let [slug (:plan (first (sp/query-entities storage :org {:name org})))
          plan (get plans slug default-plan)]
      {:plan (or slug "free")
       :fns {:used (entity-count storage org :fn)
             :max (:max-fns plan)}
       :list-items {:used (entity-count storage org :binding-list-item)
                    :max (:max-list-items plan)}})))


(defn install!
  "Install the plan-driven seams (closed over the platform `storage`): the
   effect allow-list resolver (compile-runtime), the row-cap check + the
   read-side quota-status reader (tenancy.storage). `storage` reads the
   tenant-forbidden `:org` row unrestricted, on the tenant's behalf."
  [storage]
  (reset! cr/cloud-allowed-effects-resolver (partial allowed-effects-for storage))
  (reset! ts/entity-quota-exceeded? (partial over-entity-quota? storage))
  (reset! ts/quota-status-fn (partial quota-status storage)))


(defn uninstall!
  "Clear both seams (→ locked default effects, no row-cap). Called on tenancy-
   system halt so the process-global resolvers are lifecycle-bound and can't
   leak a stale storage into a later test in the same JVM."
  []
  (reset! cr/cloud-allowed-effects-resolver nil)
  (reset! ts/entity-quota-exceeded? nil)
  (reset! ts/quota-status-fn nil))
