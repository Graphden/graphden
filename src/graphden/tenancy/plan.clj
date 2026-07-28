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
  "Plan slug → `{:effects <allow-list> :max-fns <n|nil> :max-list-items <n|nil>
   :dedicated-executor? <bool> :max-services <n>}`. `free` is the locked default
   (`#{:db :state :time :random}`); `network` additionally allows outbound
   `:network` (external HTTP / SQL, itself guarded by the egress broker — #5) and
   lifts the ceilings; `dedicated` is the SERVICE tier (see below). A nil ceiling
   = uncapped.

   TWO row ceilings, because a tenant controls TWO independent DB-growth vectors:
   `:max-fns` bounds `:fn` rows (slots / bindings scale with fns, so fn-count is
   their proxy), and `:max-list-items` bounds `:binding-list-item` rows — SEQUENCE
   content, appended one row (+ one version row) per item, so NOT bounded by fn
   count. Without the second ceiling a tenant balloons the shared DB with one fn
   + an unbounded list.

   `:dedicated-executor?` is the SERVICE gate (task #6, FLEET_RFC §7.1). A tenant
   `:service` is a PERSISTENT process running the tenant's own graph; the shipped
   effect gate bounds WHAT it does but NOT its CPU/heap/threads, and the JVM has
   no hard per-thread heap cap — so a continuous tenant service is only safe on a
   runtime the tenant does not share (a cgroup-limited `:executor-orgs #{org}`
   pod). The shared `free`/`network` tiers therefore set `:dedicated-executor?
   false` (no services); only `dedicated` — provisioned its own limited pod —
   sets it true and grants a `:max-services` allowance. Extend here as tiers are
   added."
  {"free"      {:effects cr/default-cloud-allowed-effects
                :max-fns 500
                :max-list-items 50000
                :dedicated-executor? false
                :max-services 0}
   "network"   {:effects (conj cr/default-cloud-allowed-effects :network)
                :max-fns 5000
                :max-list-items 500000
                :dedicated-executor? false
                :max-services 0}
   "dedicated" {:effects (conj cr/default-cloud-allowed-effects :network)
                :max-fns 5000
                :max-list-items 500000
                :dedicated-executor? true
                :max-services 20}})


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


(defn dedicated-executor?
  "True when tenant `org`'s plan grants a DEDICATED (cgroup-limited) executor —
   the prerequisite for creating a `:service` (task #6, FLEET_RFC §7.1). A
   persistent tenant service runs continuous tenant code, which is only safe on a
   runtime the tenant doesn't share; the shared `free`/`network` tiers return
   false, so tenant service creation stays gated to the `dedicated` tier. Public
   org / no org → false (the platform provisions its own services out-of-band, not
   through this tenant gate)."
  [storage org]
  (boolean (:dedicated-executor? (tenant-plan storage org))))


(defn max-services-for
  "The number of `:service` rows tenant `org`'s plan allows (0 on the shared
   tiers). The create-path cap (task #6 part 4) reads this once `:service`
   carries an `:org-id`. Public org / no org → 0 (not a tenant service gate)."
  [storage org]
  (or (:max-services (tenant-plan storage org)) 0))


;; The gated entities → the `{table, plan-ceiling-key}` they're measured against.
;; Table names come from this hardcoded map (no injection surface). `:fn` /
;; `:binding-list-item` are the ROW-CAP growth vectors (task #7, gated on the
;; OrgScoped create path). `:service` is the task-#6 count cap — NOT wired into
;; that create-path gate (it's not in `quota-messages`), only into
;; `entity-count` / `over-entity-quota?` so `create-tenant-service!` can enforce
;; `:max-services` off the same `count(*) WHERE org_id` machinery.
(def ^:private quota-spec
  {:fn                {:table "fn" :ceiling :max-fns}
   :binding-list-item {:table "binding_list_item" :ceiling :max-list-items}
   :service           {:table "service" :ceiling :max-services}})


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


;; The `:service` fields a tenant may set on create. `:org-id` is stamped by
;; `create-tenant-service!` (never taken from the request — that would let a
;; tenant plant a service under another org); the reconciler-managed
;; `:cardinality` / `:pool-size` default to a safe singleton when omitted.
(def ^:private tenant-service-create-fields
  [:fn-id :enabled? :restart-policy :cardinality :pool-size :branch-id])


(defn create-tenant-service!
  "Create a `:service` OWNED by tenant `org`, via the platform `storage`.
   `:service` is tenant-forbidden (Option B), so OrgScopedStorage would block a
   direct tenant write; this stamps `:org-id org` and writes below the decorator.

   Gated (task #6 / FLEET_RFC §7.1): the org's plan must grant a DEDICATED
   executor (a shared-JVM always-on tenant service is unsafe), and the org must
   be under its `:max-services` cap. Throws `:authz/forbidden` (no tenant / not a
   dedicated tier) or `:quota/service-limit` (at cap). `data` is the already-
   coerced service config; only `tenant-service-create-fields` are honoured and
   `:org-id` is always the caller's own org."
  [storage org data]
  (when (or (nil? org) (= org tc/public-org))
    (throw (ex-info "Services require an authenticated tenant."
                    {:type :authz/forbidden :reason :service/no-tenant})))
  (when-not (dedicated-executor? storage org)
    (throw (ex-info "Your plan does not include services. Upgrade to a dedicated plan to run persistent services."
                    {:type :authz/forbidden :org org :reason :service/tier-required})))
  (when (over-entity-quota? storage org :service)
    (throw (ex-info "You've reached your plan's service limit. Upgrade your plan to run more services."
                    {:type :quota/service-limit :org org :reason :service/limit})))
  (sp/create-entity storage :service
                    (-> data
                        (select-keys tenant-service-create-fields)
                        (assoc :org-id org))))


(defn list-tenant-services!
  "Every `:service` OWNED by tenant `org` (its `:org-id` rows), read via the
   platform `storage` — the entity is tenant-forbidden, so the tenant reads its
   own through this seam, filtered by `:org-id` (never seeing another org's or a
   platform service). Public org / no org → nil."
  [storage org]
  (when (and org (not= org tc/public-org))
    (vec (sp/query-entities storage :service {:org-id org}))))


(defn- owned-service!
  "Read `:service` `service-id` via base and assert tenant `org` OWNS it (its
   `:org-id` matches). Throws `:authz/forbidden` when the id is unknown, is a
   PLATFORM service (nil `:org-id`), or belongs to another org — a single opaque
   error so a tenant can't probe another org's service ids. Returns the row."
  [storage org service-id]
  (let [svc (when service-id (sp/read-entity storage :service service-id))]
    (when (or (nil? svc) (nil? org) (= org tc/public-org) (not= org (:org-id svc)))
      (throw (ex-info "Service not found."
                      {:type :authz/forbidden :org org :reason :service/not-owned})))
    svc))


(defn update-tenant-service!
  "Update a `:service` tenant `org` OWNS (ownership-checked via `owned-service!`),
   through the platform `storage` (the entity is tenant-forbidden). Only
   `tenant-service-create-fields` are writable — never `:org-id` (owner is
   immutable) — so a tenant can retarget the fn / toggle `:enabled?` / change the
   restart policy but can't reassign the service to another org. `data` is the
   already-coerced desired config. Returns the updated row."
  [storage org service-id data]
  (owned-service! storage org service-id)
  (sp/update-entity storage :service service-id
                    (select-keys data tenant-service-create-fields)))


(defn delete-tenant-service!
  "Delete a `:service` tenant `org` OWNS (ownership-checked), through the platform
   `storage`. Deletion is always allowed for an owned row — even a downgraded org
   may clean up its services. Returns the delete result."
  [storage org service-id]
  (owned-service! storage org service-id)
  (sp/delete-entity storage :service service-id))


(defn install!
  "Install the plan-driven seams (closed over the platform `storage`): the
   effect allow-list resolver (compile-runtime), the row-cap check, the
   read-side quota-status reader, and the tenant service create/list seams
   (tenancy.storage). `storage` reads / writes the tenant-forbidden `:org` /
   `:service` rows unrestricted, on the tenant's behalf."
  [storage]
  (reset! cr/cloud-allowed-effects-resolver (partial allowed-effects-for storage))
  (reset! ts/entity-quota-exceeded? (partial over-entity-quota? storage))
  (reset! ts/quota-status-fn (partial quota-status storage))
  (reset! ts/create-tenant-service-fn (partial create-tenant-service! storage))
  (reset! ts/list-tenant-services-fn (partial list-tenant-services! storage))
  (reset! ts/update-tenant-service-fn (partial update-tenant-service! storage))
  (reset! ts/delete-tenant-service-fn (partial delete-tenant-service! storage)))


(defn uninstall!
  "Clear every seam (→ locked default effects, no row-cap, no service ops).
   Called on tenancy-system halt so the process-global resolvers are lifecycle-
   bound and can't leak a stale storage into a later test in the same JVM."
  []
  (reset! cr/cloud-allowed-effects-resolver nil)
  (reset! ts/entity-quota-exceeded? nil)
  (reset! ts/quota-status-fn nil)
  (reset! ts/create-tenant-service-fn nil)
  (reset! ts/list-tenant-services-fn nil)
  (reset! ts/update-tenant-service-fn nil)
  (reset! ts/delete-tenant-service-fn nil))
