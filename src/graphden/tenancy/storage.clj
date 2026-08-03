(ns graphden.tenancy.storage
  "OrgScopedStorage — the tenancy addon's storage decorator (PLATFORM_PLAN
   §3.0). Stamps the current org (`tenancy.context/current-org`) on writes
   and filters reads to {current-org, public} — the §2.8 own-plus-public
   model, so platform packages stay visible inside every org while a
   tenant's own rows stay private.

   It wraps a base storage and delegates every NON-tenant protocol verbatim
   (Storage / Introspection / Constraints / Codec / ErrorClassifier /
   ExecutionGraph). Only the CRUD + batch-CRUD paths carry org logic, and
   only for entities in `scoped?` — platform/system entities (schema,
   executions, branches) are global.

   Placement: BENEATH versioning — `Versioned(OrgScoped(Postgres))` — so the
   branch-router's `vs/unwrap` lands on this layer and the tenant filter
   survives the unwrap (ADR §3.0 nuance 1). Postgres RLS is the
   belt-and-suspenders second layer (the decorator is the app-level filter;
   RLS is the can't-be-bypassed-by-a-raw-query backstop, incl. the batch
   update/delete own-guards this layer leaves to it)."
  (:require
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.grant :as grant]))


(def default-scoped-entities
  "Entities that carry a tenant (`:org-id`). Everything else is global.

   `:fn-execution` IS scoped (§4): the create runs synchronously in the
   request scope (stamped with `*current-org*`), and the terminal-status UPDATE
   runs in the completion future — which inherits `*current-org*` by binding
   conveyance (`record-completion!`'s `(future …)` + `run-future`'s
   `bound-fn*`), so the own-guard passes. Service / cron executions run with no
   request scope → public, which is correct (platform-level). The child
   `:fn-execution-arg` rows stay id-gated (reachable only via an already-scoped
   execution-id).

   `:branch` IS scoped (§4): the branch-router resolves it INSIDE the request
   scope (so `*current-org*` is bound — see `dispatch`), giving each tenant its
   own branches + `main`. The per-branch compiled ctx stays org-AGNOSTIC (Design
   B: `:compile-storage` reads structure unscoped); isolation is the org-scoped
   `:storage` at runtime. Names stay globally UNIQUE for now (a tenant can't
   reuse another org's branch name); per-org names need NULLS-NOT-DISTINCT.

   `:ns` IS scoped: core / package namespaces are created by sync on the base
   (unwrapped) storage → NULL org ≡ public / shared; a tenant's namespaces are
   stamped + isolated. Without this, namespaces were a GLOBAL tree — any tenant
   could enumerate every org's namespace layout, delete another org's namespace
   (its org-scoped fns are invisible → looks empty → delete proceeds), or
   rename/reparent to tamper with grant path coverage. `(parent-id, name)` stays
   globally unique (same limitation as the other scoped entities).

   `:package-install` IS scoped: a pin (\"this branch uses package P at V\") is
   tenant desired-state, written in the request scope by `:install-package` /
   `:set-package-pin`. Scoping stamps `:org-id` on the pin + RLS-filters reads,
   so one org can neither see nor repoint another org's pins. (The pin's
   `:branch-id` already rides the org-scoped `:branch`; this is the
   defence-in-depth second layer.)"
  #{:fn :slot :fn-slot :binding :binding-list-item :fn-execution :branch :ns
    :package-install})


(def tenant-forbidden-entities
  "Platform entities a tenant (org ≠ public) may neither read nor write —
   they aren't org-isolated, so a tenant touching them escalates out of, or
   enumerates across, the sandbox:

   - `:service` — NOT org-scoped by construction (Option B, task #6 /
     FLEET_RFC §7.1). The reconciler runs each service through the
     `cr/run-service-scoped` effect sandbox now, so the old escape
     (unsandboxed `:http-server`) is closed — but `:service` STAYS forbidden
     because the platform reconciler must read EVERY org's services in one
     pass, and org-scoping the entity would hide tenant rows from the platform
     under FORCE ROW LEVEL SECURITY. Tenant services are instead created / listed
     through dedicated endpoints (base storage, `:org-id`-stamped), gated to the
     dedicated (own cgroup-limited pod) tier.
   - `:grant`   — write: grant itself `:admin`; read: enumerate every org's
     grants (the grants panel's `:list-grants`).
   - `:domain`  — hijack / enumerate custom-domain → org routing.
   - `:org`     — the orgs registry (§3.4). Platform-managed: tenants register
     and configure their org through dedicated endpoints / the editor, never
     by writing the registry row, and must not enumerate other orgs.
   - `:token`   — storage-backed auth tokens (§3.4 #1). Write: a tenant could
     mint itself a token for ANY org (full escalation); read: enumerate /
     exfiltrate every user's token hashes. Strictly platform-managed.
   - `:user`    — the user registry (§4.1). Write: a tenant could create
     accounts in other orgs; read: enumerate every user + their password
     hashes. `login!` reads it in the platform context, before any session.
   - `:app-route` — an org's named apps (Track C, `(org, label) →
     handler-fn-id`). Write: a tenant could route `<label>.<victim>.<base>` at
     its OWN fn (host hijack); read: enumerate every org's apps. The router
     reads it in the platform context; tenant management is org-stamped.

   `:branch` and `:fn-execution` were once here too; both are now ORG-SCOPED
   (see `default-scoped-entities`) — a tenant gets its OWN branches/executions
   instead of being locked out.

   Platform / admin (public org) is unrestricted. New privileged entity types
   MUST be added here."
  #{:service :grant :domain :org :token :user :app-route})


(defn- row-org
  "A row's tenant — NULL `:org-id` (the column default for core writes that
   never went through this decorator) means the shared public org."
  [row]
  (or (:org-id row) tc/public-org))


(defn- visible?
  "A row is readable by the current org iff it belongs to that org or to
   the shared public org."
  [row]
  ;; `conj`, not `#{a b}` — the set literal throws on a duplicate key when
  ;; the current org IS the public org.
  (contains? (conj #{tc/public-org} (tc/current-org)) (row-org row)))


(defn- own?
  "A row is writable by the current org iff it belongs to that org — public
   and other-org rows are read-only here (RLS enforces the same)."
  [row]
  (= (tc/current-org) (row-org row)))


(defn- stamp
  [data]
  (assoc data :org-id (tc/current-org)))


(def ^:private ref-fields
  "Graph edges that point at another row, by owning entity. Each entry is
   `field → target-entity`. `:parent-ids` is the one vector-valued edge.

   These are exactly the edges `executor.compile-runtime/read-graph`
   has to be able to follow after it filters the graph down to one
   executor's org shard."
  {:fn                {:parent-ids :fn
                       :return-type-fn-id :fn
                       :base-fn-id :fn
                       :element-fn-id :fn}
   :slot              {:type-fn-id :fn}
   :fn-slot           {:slot-id :slot}
   :binding           {:ref-fn-id :fn
                       :type-override-fn-id :fn}
   :binding-list-item {:ref-fn-id :fn}})


(defn- reject-cross-org-refs!
  "Refuse a write whose graph edges leave `{own-org, public}`.

   Until now this held only EMERGENTLY: a tenant's reads are filtered to
   `{own-org, public}`, so the editor could never offer another org's fn
   as a ref target. Nothing enforced it. That is fine while every pod
   compiles every org, and unsound the moment a pod compiles only its
   own shard — a binding pointing into an org this pod doesn't hold
   would compile to a dangling ref.

   So the property the shard depends on is now checked where the edge is
   written, not merely implied by what the writer could see.

   `base` is the UNSCOPED storage: we need the target's true `:org-id`,
   which the org filter would hide precisely in the case we want to
   catch.

   Skipped for the public org — not for lack of rigour. A public write
   cannot form a cross-org edge in the first place: `visible?` hides
   every tenant row from the public org, so there is no foreign
   target-id to name. Meanwhile the platform writes its entire package
   graph through this decorator at boot (thousands of rows, most with
   `:parent-ids`), and the check costs one `read-entity` per edge.
   Paying that on every cloud startup to re-prove what the read filter
   already guarantees is not a trade worth making.

   A privileged path that deliberately wrote a public→tenant edge would
   go through the BASE storage and never reach this guard anyway."
  [base entity-name data]
  (when-let [fields (and data
                         (not (tc/current-platform-tier?))
                         (get ref-fields entity-name))]
    (let [org (tc/current-org)
          ;; `row-org` normalises a NULL `:org-id` to the public org, so
          ;; un-owned platform rows are covered by the public branch.
          allowed? (fn [target-org]
                     (or (tc/platform-tier? target-org)
                         (= org target-org)))]
      (doseq [[field target-entity] fields
              :let [v (get data field)]
              :when (some? v)
              target-id (if (sequential? v) v [v])
              :when (some? target-id)]
        (let [target (sp/read-entity base target-entity target-id)
              target-org (some-> target row-org)]
          (when (and target (not (allowed? target-org)))
            (throw (ex-info (str "forbidden: " entity-name "." field
                                 " points at a row owned by another org")
                            {:type :authz/forbidden
                             :entity entity-name
                             :field field
                             :org org
                             :target-org target-org}))))))))


(defn- guard-write!
  "Enforce tenant write policy, then run the per-namespace write guard when
   one is configured. Both throw `:authz/forbidden` on a denied write (caught
   → 403 by the request-scope).

   First, the unconditional tenant invariant (independent of any grant store):
   a tenant may not write a `tenant-forbidden-entities` type — this is what
   keeps a tenant from deploying a `:service` directly (bypassing the
   dedicated-tier gate + `:org-id` stamp of the service-create endpoint) or
   escalating via `:grant`.

   Then the cross-org edge check (see `reject-cross-org-refs!`), which is
   what lets an executor compile a single org's shard of the graph.

   `id` is the entity's id on update / delete (nil on create), so the guard can
   read the existing row to resolve its namespace when `data` doesn't carry the
   identifying fields (a value-only binding update, or a delete)."
  [base authorize-write entity-name data id]
  ;; A platform-admin operator (org graphden, holding the grant) may write
  ;; these platform entities — the admin console. They are global (not in
  ;; `default-scoped-entities`), so no `:org-id` stamping is corrupted; the
  ;; write sets the TARGET org as a data field. Everything else — the effect
  ;; gate, per-namespace :fn authz, quotas — is untouched, so the operator
  ;; still can't edit a tenant's graph or escape the sandbox with their own.
  (when (and (not (tc/current-platform-tier?))
             (not (grant/current-platform-admin?))
             (contains? tenant-forbidden-entities entity-name))
    (throw (ex-info (str "forbidden: tenants may not write privileged entity " entity-name)
                    {:type :authz/forbidden :entity entity-name})))
  (reject-cross-org-refs! base entity-name data)
  (when authorize-write
    (authorize-write entity-name data id)))


(defn- tenant-hidden?
  "True when the current tenant must not READ this entity type — the read
   mirror of `tenant-forbidden-entities`. `:grant` / `:domain` / `:service`
   are platform state; a tenant listing them (e.g. the grants panel's
   `:list-grants`, which queries `:grant`) would enumerate EVERY org's rows.
   Platform (public org) reads them normally. Authz is unaffected: the
   grant-store reads `:grant` from the BASE storage, not this decorator."
  [entity-name]
  (and (not (tc/current-platform-tier?))
       (not (grant/current-platform-admin?))
       (contains? tenant-forbidden-entities entity-name)))


;; Row-cap seam (task #7). A `(fn [org entity-name] boolean)` — true when `org`
;; is at/over its plan ceiling for that gated entity.
;; `graphden.tenancy.plan/install!` sets it (closed over the platform storage);
;; nil (no addon / single-tenant) = no cap. Lifecycle-bound by the addon halt so
;; it can't leak a stale storage across tests in one JVM.
(defonce entity-quota-exceeded? (atom nil))


;; Read-side companion to `entity-quota-exceeded?`: `(fn [org] → {:plan …
;; :fns {:used :max} :list-items {:used :max}})`, for the editor's proactive
;; usage display (task #8-frontend). `graphden.tenancy.plan/install!` sets it
;; (closed over the platform storage); nil (no addon) → the endpoint returns
;; null. Same lifecycle-binding rationale as the cap seam above.
(defonce quota-status-fn (atom nil))


;; Tenant service create / list seams (task #6 part 4). `:service` is
;; tenant-forbidden (Option B — see `tenant-forbidden-entities`), so a tenant
;; can't create / list it through this decorator; these seams let the tenant-
;; facing endpoints do it via the platform base storage, gated on the org's plan
;; (dedicated tier + `:max-services` cap) and `:org-id`-stamped / -filtered.
;; `graphden.tenancy.plan/install!` sets them (closed over base); nil (no addon)
;; → the endpoints no-op. Same lifecycle-binding rationale as the seams above.
(defonce create-tenant-service-fn (atom nil))


(defonce list-tenant-services-fn (atom nil))


;; Update / delete companions (task #6 part 4b). Both ownership-gated by
;; `:org-id` inside the seam (a tenant may only mutate its own services, never a
;; platform or sibling-org row). Same install / lifecycle contract as above.
(defonce update-tenant-service-fn (atom nil))


(defonce delete-tenant-service-fn (atom nil))


;; Tenant app-route (named-app) CRUD seams (Track C4a). `:app-route` is
;; tenant-forbidden (host-hijack / enumeration guard — see
;; `tenant-forbidden-entities`), so the tenant-facing `/api/orgs/apps` endpoints
;; reach it via the platform base storage, `:org`-stamped on create and
;; `:org`-filtered on list; update / delete are ownership-gated by `:org` in the
;; seam. `graphden.tenancy.plan/install!` sets them (closed over base); nil (no
;; addon) → the endpoints no-op. Same lifecycle-binding rationale as above.
(defonce create-tenant-app-route-fn (atom nil))


(defonce list-tenant-app-routes-fn (atom nil))


(defonce update-tenant-app-route-fn (atom nil))


(defonce delete-tenant-app-route-fn (atom nil))


;; The tenant-controlled DB-growth entities the create-path gates, → the
;; user-facing over-limit message. `:fn` and `:binding-list-item` are the two
;; INDEPENDENT vectors: fns (slots/bindings scale with them) and sequence
;; content (one row per append, unbounded by fn count). See `plan/plans`.
(def ^:private quota-messages
  {:fn "You've reached your plan's function limit. Upgrade your plan to create more functions."
   :binding-list-item "You've reached your plan's list-size limit. Upgrade your plan to store more list items."})


(defn- enforce-entity-quota!
  "Reject a tenant create of a GATED growth entity (`:fn` / `:binding-list-item`)
   that is at/over the org's plan ceiling. The public / platform org is never
   capped (the installed resolver returns false for it)."
  [entity-name]
  (when-let [msg (get quota-messages entity-name)]
    (when-let [over? @entity-quota-exceeded?]
      (let [org (tc/current-org)
            ;; Fail-open: a quota-check that THROWS (a DB blip, or a stale
            ;; resolver another test's init-key left in the process-global atom)
            ;; must never block a legitimate write — the row-cap is soft
            ;; abuse-prevention, not a correctness invariant. Mirrors
            ;; `cr/cloud-allowed-effects-for`'s fail-safe.
            over (try (boolean (over? org entity-name)) (catch Exception _ false))]
        (when over
          ;; The ex-MESSAGE is the user-facing text: web/errors surfaces it
          ;; verbatim because `:quota` is a message-visible family (NOT the org
          ;; name — that would leak). `:type` maps to HTTP 429. `:reason`
          ;; mirrors it for any consumer that prefers a carried reason.
          (throw (ex-info msg
                          {:type :quota/entity-limit :org org :entity entity-name :reason msg})))))))


(defrecord OrgScopedStorage
  [base scoped? authorize-write]

  sp/StorageCRUD

  (create-entity
    [_ entity-name data]
    (guard-write! base authorize-write entity-name data nil)
    (enforce-entity-quota! entity-name)
    (sp/create-entity base entity-name
                      (cond-> data (scoped? entity-name) stamp)))


  (read-entity
    [_ entity-name id]
    (when-not (tenant-hidden? entity-name)
      (let [row (sp/read-entity base entity-name id)]
        (when-not (and row (scoped? entity-name) (not (visible? row))) row))))


  (update-entity
    [_ entity-name id data]
    (guard-write! base authorize-write entity-name data id)
    (if (scoped? entity-name)
      ;; Only own rows are writable, and a tenant can never be reassigned.
      (when (some-> (sp/read-entity base entity-name id) own?)
        (sp/update-entity base entity-name id (dissoc data :org-id)))
      (sp/update-entity base entity-name id data)))


  (delete-entity
    [_ entity-name id]
    ;; Deletes are namespaced writes too (§4.3): an `:append-list` user removing
    ;; a list-item, a `:write` user deleting a binding. `data` is nil — the guard
    ;; reads the row by id to resolve its namespace.
    (guard-write! base authorize-write entity-name nil id)
    (if (scoped? entity-name)
      (when (some-> (sp/read-entity base entity-name id) own?)
        (sp/delete-entity base entity-name id))
      (sp/delete-entity base entity-name id)))


  (query-entities
    [_ entity-name where]
    (if (tenant-hidden? entity-name)
      []
      (cond-> (sp/query-entities base entity-name where)
        (scoped? entity-name) (->> (filterv visible?)))))


  (query-entities
    [_ entity-name where opts]
    (if (tenant-hidden? entity-name)
      []
      (cond-> (sp/query-entities base entity-name where opts)
        (scoped? entity-name) (->> (filterv visible?)))))


  (query-latest-per-group
    [_ entity-name where group-cols]
    (if (tenant-hidden? entity-name)
      []
      (cond-> (sp/query-latest-per-group base entity-name where group-cols)
        (scoped? entity-name) (->> (filterv visible?)))))


  sp/StorageBatchCRUD

  (create-entities
    [_ entity-name data-seq]
    (run! #(guard-write! base authorize-write entity-name % nil) data-seq)
    (sp/create-entities base entity-name
                        (cond->> data-seq (scoped? entity-name) (mapv stamp))))


  (read-entities
    [_ entity-name ids]
    (if (tenant-hidden? entity-name)
      []
      (cond-> (sp/read-entities base entity-name ids)
        (scoped? entity-name) (->> (filterv visible?)))))


  (update-entities
    [_ entity-name data-seq]
    ;; Per-row write guard (mirrors create/upsert): the tenant-forbidden
    ;; type block has NO RLS backstop — those tables carry no `:org-id` — so
    ;; a batch update/delete on them must be guarded here, not "left to RLS".
    (run! #(guard-write! base authorize-write entity-name % (:id %)) data-seq)
    ;; Strip any org reassignment.
    (sp/update-entities base entity-name
                        (cond->> data-seq
                          (scoped? entity-name) (mapv #(dissoc % :org-id)))))


  (upsert-entities
    [_ entity-name data-seq]
    (run! #(guard-write! base authorize-write entity-name % nil) data-seq)
    (sp/upsert-entities base entity-name
                        (cond->> data-seq (scoped? entity-name) (mapv stamp))))


  (delete-entities
    [_ entity-name ids]
    (run! #(guard-write! base authorize-write entity-name nil %) ids)
    (sp/delete-entities base entity-name ids))


  (query-ref-many-owners
    [_ entity-name field-name target-id]
    (if (tenant-hidden? entity-name)
      []
      (let [owner-ids (sp/query-ref-many-owners base entity-name field-name target-id)]
        (if (and (scoped? entity-name) (seq owner-ids))
          ;; This was the ONLY read delegating to base UNFILTERED, and the
          ;; junction table it reads (e.g. `fn_parent_ids`) has no RLS
          ;; backstop — so a tenant could reverse-ref a shared/public row
          ;; and learn owner-ids (and their count) across EVERY org (e.g. the
          ;; "parent of N graphs" message on deleting a public base-fn).
          ;; Post-filter owner-ids to rows the current org may SEE (own +
          ;; public), matching every other read method.
          (let [id->row (sp/read-entities base entity-name (vec owner-ids))]
            (into []
                  (keep (fn [id]
                          (when-let [row (get id->row id)]
                            (when (visible? row) id))))
                  owner-ids))
          owner-ids))))


  sp/Storage

  (initialize [_ schema] (sp/initialize base schema))


  (close [_] (sp/close base))


  sp/StorageIntrospection

  (current-entities [_] (sp/current-entities base))


  (current-fields [_ entity-name] (sp/current-fields base entity-name))


  (current-enums [_] (sp/current-enums base))


  (current-enum-values [_ enum-name] (sp/current-enum-values base enum-name))


  (schema-metadata [_] (sp/schema-metadata base))


  sp/GraphConstraints

  (validate-no-dependency-cycle!
    [_ owner-fn-id ref-fn-id]
    (sp/validate-no-dependency-cycle! base owner-fn-id ref-fn-id))


  sp/ConstraintHelpers

  (collect-dependency-chain
    [_ fn-id]
    (sp/collect-dependency-chain base fn-id))


  sp/StorageValueCodec

  (encode-value [_ value field-spec] (sp/encode-value base value field-spec))


  (decode-value [_ value field-spec] (sp/decode-value base value field-spec))


  (encode-row [_ row field-specs] (sp/encode-row base row field-specs))


  (decode-row [_ row field-specs] (sp/decode-row base row field-specs))


  sp/StorageErrorClassifier

  (classify-error [_ exception] (sp/classify-error base exception))


  (wrap-error
    [_ exception operation context]
    (sp/wrap-error base exception operation context))


  sp/ExecutionGraph

  (resolve-execution-graph [_ fn-id] (sp/resolve-execution-graph base fn-id)))


(defn org-scoped-storage
  "Wrap `base` so reads see {current-org, public} and writes stamp the
   current org. `scoped-entities` (default `default-scoped-entities`) is the
   set of entity names that carry a tenant. `authorize-write` (optional) is a
   `(fn [entity-name data])` write guard — `tenancy.authz/authorize-writer`
   — that throws `:authz/forbidden` on a denied per-namespace write; nil =
   no per-namespace enforcement."
  ([base] (org-scoped-storage base default-scoped-entities nil))
  ([base scoped-entities] (org-scoped-storage base scoped-entities nil))
  ([base scoped-entities authorize-write]
   (->OrgScopedStorage base (set scoped-entities) authorize-write)))
