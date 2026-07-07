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
    [clojure.string :as str]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.domain :as domain]))


(defn set-org-handler!
  "Point the current tenant's org at `fn-id` as its app handler. Throws
   `:authz/forbidden` when there is no tenant org (public/unauthenticated),
   when `fn-id` isn't readable by the tenant (not its own / public), or when
   the tenant's `:org` row is missing (a token for a deleted org). Returns the
   updated `:org` row on success — never a nil the handler would render as a
   200 `\"nil\"` body."
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
                 (if-let [row (first (sp/query-entities storage :org {:name org}))]
                   (sp/update-entity storage :org (:id row) {:handler-fn-id fn-id})
                   (throw (ex-info "forbidden: no :org row for this tenant"
                                   {:type :authz/forbidden :org org}))))))


(defn verify-domain!
  "Self-serve DNS verification (PLATFORM_PLAN §3.4 #2 follow-up): the current
   tenant proves it OWNS `hostname` — a `graphden-verify=<org>` DNS-TXT record —
   and the row flips to `:verified?` (so the app-router begins serving the org
   for that host). Throws `:authz/forbidden` when there's no tenant org or
   `hostname` isn't registered to it, `:domain/unverified` when DNS doesn't
   prove ownership. `lookup-txt` is injectable for tests (default real DNS).

   Why a seam: `:domain` is tenant-forbidden (read + write), so the tenant can
   neither inspect nor flip the row directly. This VALIDATES the row belongs to
   the tenant's org, runs the privileged DNS lookup (a network call tenant
   graphs can't make), and does the single `:verified?` update under a temporary
   public escalation — only for a host already assigned to the tenant's org."
  ([ctx hostname] (verify-domain! ctx hostname domain/dns-txt-records))
  ([ctx hostname lookup-txt]
   (let [org (tc/current-org)
         storage (:storage ctx)
         host (some-> hostname str/lower-case)]
     (when (= org tc/public-org)
       (throw (ex-info "forbidden: not a tenant request"
                       {:type :authz/forbidden})))
     ;; `:domain` is tenant-hidden/forbidden — read + update under a temporary
     ;; public escalation, but ONLY the row whose org is the tenant's own.
     (tc/with-org tc/public-org
                  (let [row (first (sp/query-entities storage :domain {:hostname host}))]
                    (when-not (and row (= (:org row) org))
                      (throw (ex-info "forbidden: domain not registered to this org"
                                      {:type :authz/forbidden :hostname hostname})))
                    (when-not (domain/verify-domain-ownership host org lookup-txt)
                      (throw (ex-info "domain ownership not proven by DNS"
                                      {:type :domain/unverified :hostname hostname})))
                    (sp/update-entity storage :domain (:id row) {:verified? true}))))))
