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
     per `:fn` create to reject a tenant over its row-cap.
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
  "Plan slug → `{:effects <allow-list> :max-fns <n|nil>}`. `free` is the locked
   default (`#{:db :state :time :random}`, capped); `network` additionally
   allows outbound `:network` (external HTTP / SQL, itself guarded by the egress
   broker — #5) and lifts the row-cap. `:max-fns` nil = uncapped. Extend here as
   tiers are added (e.g. a `:process` service tier — #6)."
  {"free"    {:effects cr/default-cloud-allowed-effects
              :max-fns 500}
   "network" {:effects (conj cr/default-cloud-allowed-effects :network)
              :max-fns 5000}})


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


(defn fn-count
  "How many `:fn` identity rows tenant `org` owns — a raw `count(*)`, no row
   load. `storage` must be (wrap) a PostgresStorage exposing `:pool`; when it
   doesn't (misconfig), returns nil so the caller fails OPEN (a broken count
   must not block a tenant's writes)."
  [storage org]
  (when-let [ds (:pool storage)]
    (-> (jdbc/execute-one! ds ["SELECT count(*) AS n FROM \"fn\" WHERE org_id = ?" org]
                           {:builder-fn rs/as-unqualified-lower-maps})
        :n)))


(defn over-entity-quota?
  "True when tenant `org` already holds ≥ its plan's `:max-fns`. Uncapped plans
   / the public org / an uncountable storage → false (never over)."
  [storage org]
  (boolean
    (when-let [cap (:max-fns (tenant-plan storage org))]
      (when-let [n (fn-count storage org)]
        (>= n cap)))))


(defn install!
  "Install both plan-driven seams (closed over the platform `storage`): the
   effect allow-list resolver (compile-runtime) and the row-cap check
   (tenancy.storage). `storage` reads the tenant-forbidden `:org` row unrestricted,
   on the tenant's behalf."
  [storage]
  (reset! cr/cloud-allowed-effects-resolver (partial allowed-effects-for storage))
  (reset! ts/entity-quota-exceeded? (partial over-entity-quota? storage)))


(defn uninstall!
  "Clear both seams (→ locked default effects, no row-cap). Called on tenancy-
   system halt so the process-global resolvers are lifecycle-bound and can't
   leak a stale storage into a later test in the same JVM."
  []
  (reset! cr/cloud-allowed-effects-resolver nil)
  (reset! ts/entity-quota-exceeded? nil))
