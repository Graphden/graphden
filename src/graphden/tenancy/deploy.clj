(ns graphden.tenancy.deploy
  "Self-serve deploy (PLATFORM_PLAN §3.4 step 4b): an authenticated tenant
   points its OWN org at one of its OWN fns as the app handler. This is the
   controlled-privilege core — the security-critical piece of self-serve.

   Why a seam and not a plain base-fn: `:org` is in
   `tenancy.storage/tenant-forbidden-entities`, so a tenant can neither read
   nor write the registry directly (read-deny + write-deny). `set-org-handler!`
   VALIDATES (the org is the token's; the fn is readable by the tenant, i.e.
   its own or public) and then performs the single `:org` update under a
   temporary public escalation — the ONLY write a tenant can cause to its org
   row, and only to `:handler-fn-id`, only for the org named by its own token."
  (:require
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]))


(defn set-org-handler!
  "Point the current tenant's org at `fn-id` as its app handler. Throws
   `:authz/forbidden` when there is no tenant org (public/unauthenticated) or
   `fn-id` isn't readable by the tenant (not its own / public). Returns the
   updated `:org` row on success, or nil when the org row is missing."
  [ctx fn-id]
  (let [org (tc/current-org)
        storage (:storage ctx)]
    (when (= org tc/public-org)
      (throw (ex-info "forbidden: not a tenant request"
                      {:type :authz/forbidden})))
    ;; Ownership check IN the tenant context: OrgScoped only returns the fn if
    ;; it's the tenant's own (or public) — so a tenant can't point its app at
    ;; another org's fn.
    (when-not (sp/read-entity storage :fn fn-id)
      (throw (ex-info "forbidden: handler fn not accessible to this org"
                      {:type :authz/forbidden :fn-id fn-id})))
    ;; The one controlled write: read + update the tenant's OWN `:org` row
    ;; under a temporary public escalation (`:org` is otherwise tenant-hidden /
    ;; forbidden). Scoped to `name = org`, the org from the authenticated
    ;; token, so it can never touch another org's row.
    (tc/with-org tc/public-org
                 (when-let [row (first (sp/query-entities storage :org {:name org}))]
                   (sp/update-entity storage :org (:id row) {:handler-fn-id fn-id})))))
