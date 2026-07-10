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
    [graphden.tenancy.context :as tc]))


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

   - `:service` — runs via the reconciler in an UNSANDBOXED ctx (no effect
     gate), so a tenant deploying an `:http-server` service would escape the
     cloud effect restrictions (env / io / network / process) entirely.
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

   `:branch` and `:fn-execution` were once here too; both are now ORG-SCOPED
   (see `default-scoped-entities`) — a tenant gets its OWN branches/executions
   instead of being locked out.

   Platform / admin (public org) is unrestricted. New privileged entity types
   MUST be added here."
  #{:service :grant :domain :org :token :user})


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


(defn- guard-write!
  "Enforce tenant write policy, then run the per-namespace write guard when
   one is configured. Both throw `:authz/forbidden` on a denied write (caught
   → 403 by the request-scope).

   First, the unconditional tenant invariant (independent of any grant store):
   a tenant may not write a `tenant-forbidden-entities` type — this is what
   keeps a tenant from deploying an unsandboxed `:service` or escalating via
   `:grant`.

   `id` is the entity's id on update / delete (nil on create), so the guard can
   read the existing row to resolve its namespace when `data` doesn't carry the
   identifying fields (a value-only binding update, or a delete)."
  [authorize-write entity-name data id]
  (when (and (not= (tc/current-org) tc/public-org)
             (contains? tenant-forbidden-entities entity-name))
    (throw (ex-info (str "forbidden: tenants may not write privileged entity " entity-name)
                    {:type :authz/forbidden :entity entity-name})))
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
  (and (not= (tc/current-org) tc/public-org)
       (contains? tenant-forbidden-entities entity-name)))


(defrecord OrgScopedStorage
  [base scoped? authorize-write]

  sp/StorageCRUD

  (create-entity
    [_ entity-name data]
    (guard-write! authorize-write entity-name data nil)
    (sp/create-entity base entity-name
                      (cond-> data (scoped? entity-name) stamp)))


  (read-entity
    [_ entity-name id]
    (when-not (tenant-hidden? entity-name)
      (let [row (sp/read-entity base entity-name id)]
        (when-not (and row (scoped? entity-name) (not (visible? row))) row))))


  (update-entity
    [_ entity-name id data]
    (guard-write! authorize-write entity-name data id)
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
    (guard-write! authorize-write entity-name nil id)
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
    (run! #(guard-write! authorize-write entity-name % nil) data-seq)
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
    (run! #(guard-write! authorize-write entity-name % (:id %)) data-seq)
    ;; Strip any org reassignment.
    (sp/update-entities base entity-name
                        (cond->> data-seq
                          (scoped? entity-name) (mapv #(dissoc % :org-id)))))


  (upsert-entities
    [_ entity-name data-seq]
    (run! #(guard-write! authorize-write entity-name % nil) data-seq)
    (sp/upsert-entities base entity-name
                        (cond->> data-seq (scoped? entity-name) (mapv stamp))))


  (delete-entities
    [_ entity-name ids]
    (run! #(guard-write! authorize-write entity-name nil %) ids)
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
