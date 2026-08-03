(ns graphden.packages.tenancy-admin.registration.impls
  "Impls for the operator-only provisioning routes (PLATFORM_PLAN §3.4).

   The provisioning fns themselves (`:create-org` / `:create-token` /
   `:create-domain` / `:set-org-handler` / `:set-org-execution-mode`)
   are pure graph compositions in `fns.edn` over the generic CRUD
   primitives (`:query-entities` / `:create-entity` / `:update-entity`)
   plus `:sha256-hex` — the resolve→validate→write pipelines are
   graph-visible, not hidden in Clojure. Tenant access is still denied
   by the entity guards (`:org`/`:token`/`:domain` are tenant-forbidden
   under OrgScoped), which live below this layer.

   The only Clojure left is the byo-memo drop — one interface call into
   `graphden.tenancy.context` (§3.1)."
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.plan :as plan]
    [graphden.tenancy.storage :as ts]))


(defbase known-plan-slug?
  "True when `slug` names a real plan tier, checked against `tenancy.plan/plans`
   — the single source of truth for the tier set. `set-org-plan` uses this to
   reject an operator typo before the write, so a mistyped slug can't silently
   resolve to the free default (which would break a `\"suspended\"` kill-switch)."
  [slug]
  (contains? plan/plans slug))


(defbase invalidate-byo-cache
  "Drop the byo execution-mode memo for org `name` so a mode flip takes
   effect at once instead of within the ~5s TTL. One interface call."
  [name]
  (tc/invalidate-byo-cache! name))


(defbase tenant-quota-status
  "Current org's plan usage vs ceilings, for the editor's proactive display —
   reads the installed read-side seam (`tenancy.storage/quota-status-fn`) for
   `current-org`. nil outside tenancy / for the public org. One interface call;
   the count SQL lives in `tenancy.plan/quota-status`."
  []
  (cr/record-effect! :db)
  (when-let [f @ts/quota-status-fn]
    (f (tc/current-org))))


(defn- coerce-service-fields
  "Adapt the request's parsed body (`parse-form-body-kw` → keyword keys, string
   values) into the typed `:service` shape the create seam writes: `:fn-id` /
   `:branch-id` → UUID, `:restart-policy` / `:cardinality` → keyword, `:enabled?`
   → bool, `:pool-size` → long. Blank / missing optional fields drop out;
   `:enabled?` defaults true and `:restart-policy` defaults `:always`. Pure
   boundary coercion (no storage / composition), so it lives at this HTTP seam."
  [data]
  (let [kw (fn [v] (some-> v name not-empty keyword))
        id (fn [v] (some-> v str not-empty parse-uuid))
        pool (some-> (:pool-size data) str not-empty parse-long)]
    (cond-> {:fn-id (id (:fn-id data))
             :enabled? (not= "false" (str (:enabled? data "true")))
             :restart-policy (or (kw (:restart-policy data)) :always)}
      (kw (:cardinality data)) (assoc :cardinality (kw (:cardinality data)))
      (id (:branch-id data))   (assoc :branch-id (id (:branch-id data)))
      pool                     (assoc :pool-size pool))))


(defbase tenant-create-service
  "Create a `:service` owned by the current tenant org via the installed seam
   (`tenancy.storage/create-tenant-service-fn`), which gates on the org's plan
   (dedicated tier + `:max-services` cap) and stamps `:org-id`. `data` is the
   request's parsed body; coerce its string values to the typed `:service` shape
   at this HTTP boundary. Returns the created row (nil outside tenancy)."
  [data]
  (cr/record-effect! :db)
  (when-let [f @ts/create-tenant-service-fn]
    (f (tc/current-org) (coerce-service-fields data))))


(defbase tenant-list-services
  "The current tenant org's own `:service` rows via the installed seam
   (`tenancy.storage/list-tenant-services-fn`), filtered by `:org-id`. nil
   outside tenancy / for the public org."
  []
  (cr/record-effect! :db)
  (when-let [f @ts/list-tenant-services-fn]
    (f (tc/current-org))))


(defbase tenant-update-service
  "Update a `:service` the current tenant org owns via the installed seam
   (`tenancy.storage/update-tenant-service-fn`, ownership-gated by `:org-id`).
   `data` is the parsed body — `:id` (the service uuid) + the desired config;
   coerce both at this HTTP boundary. Returns the updated row (nil outside
   tenancy)."
  [data]
  (cr/record-effect! :db)
  (when-let [f @ts/update-tenant-service-fn]
    (f (tc/current-org)
       (some-> (:id data) str not-empty parse-uuid)
       (coerce-service-fields data))))


(defbase tenant-delete-service
  "Delete a `:service` the current tenant org owns via the installed seam
   (`tenancy.storage/delete-tenant-service-fn`, ownership-gated by `:org-id`).
   `data` is the parsed body carrying `:id` (the service uuid). nil outside
   tenancy."
  [data]
  (cr/record-effect! :db)
  (when-let [f @ts/delete-tenant-service-fn]
    (f (tc/current-org) (some-> (:id data) str not-empty parse-uuid))))


(defbase tenant-list-app-routes
  "The current tenant org's own `:app-route` rows (named apps) via the installed
   seam (`tenancy.storage/list-tenant-app-routes-fn`), filtered by `:org`. nil
   outside tenancy / for the public org."
  []
  (cr/record-effect! :db)
  (when-let [f @ts/list-tenant-app-routes-fn]
    (f (tc/current-org))))


(defbase tenant-create-app-route
  "Create an `:app-route` owned by the current tenant org via the installed seam
   (`tenancy.storage/create-tenant-app-route-fn`, `:org`-stamped + label-validated
   + UNIQUE `(org, label)`). `data` is the parsed body — `:label` + `:handler-fn-id`;
   coerce the fn-id to a UUID at this HTTP boundary. Returns the created row (nil
   outside tenancy)."
  [data]
  (cr/record-effect! :db)
  (when-let [f @ts/create-tenant-app-route-fn]
    (f (tc/current-org)
       {:label (some-> (:label data) str not-empty)
        :handler-fn-id (some-> (:handler-fn-id data) str not-empty parse-uuid)})))


(defbase tenant-update-app-route
  "Point an `:app-route` the current tenant org owns at a different handler via
   the installed seam (`tenancy.storage/update-tenant-app-route-fn`, ownership-
   gated by `:org`). `data` = parsed body — `:id` (route uuid) + `:handler-fn-id`.
   Returns the updated row (nil outside tenancy)."
  [data]
  (cr/record-effect! :db)
  (when-let [f @ts/update-tenant-app-route-fn]
    (f (tc/current-org)
       (some-> (:id data) str not-empty parse-uuid)
       {:handler-fn-id (some-> (:handler-fn-id data) str not-empty parse-uuid)})))


(defbase tenant-delete-app-route
  "Delete an `:app-route` the current tenant org owns via the installed seam
   (`tenancy.storage/delete-tenant-app-route-fn`, ownership-gated by `:org`).
   `data` = parsed body carrying `:id` (route uuid). nil outside tenancy."
  [data]
  (cr/record-effect! :db)
  (when-let [f @ts/delete-tenant-app-route-fn]
    (f (tc/current-org) (some-> (:id data) str not-empty parse-uuid))))


(def impls
  {:known-plan-slug? known-plan-slug?
   :invalidate-byo-cache invalidate-byo-cache
   :tenant-quota-status tenant-quota-status
   :tenant-create-service tenant-create-service
   :tenant-list-services tenant-list-services
   :tenant-update-service tenant-update-service
   :tenant-delete-service tenant-delete-service
   :tenant-list-app-routes tenant-list-app-routes
   :tenant-create-app-route tenant-create-app-route
   :tenant-update-app-route tenant-update-app-route
   :tenant-delete-app-route tenant-delete-app-route})
