(ns graphden.packages.tenancy-admin.my-app.impls
  "Impls for the self-serve tenant base-fns (PLATFORM_PLAN §3.4 4b / #2),
   migrated out of app.admin via the route-collection seam (§6). Each invokes
   an injectable controlled-privilege seam (`:set-org-handler` / `:verify-domain`)
   that validates ownership before mutating; no seam (single-tenant) → nil."
  (:require
    [graphden.executor.defbase :refer [defbase]]))


;; Self-serve deploy (§3.4 4b): invoke the injectable `:set-org-handler` seam
;; (the tenancy addon's controlled-privilege update). No seam → nil. The seam
;; validates ownership + does the `:org` update; it throws :authz/forbidden
;; (→ 403 via the request-scope) for a public/unauthorized caller.
(defbase invoke-set-org-handler
  [fn-id]
  (when-let [seam (:set-org-handler ctx)]
    (seam ctx (cond-> fn-id (string? fn-id) parse-uuid))))


;; Self-serve DNS-verify (§3.4 #2): invoke the injectable `:verify-domain` seam
;; (the tenancy addon's controlled-privilege verification). No seam → nil. The
;; seam validates the domain belongs to the tenant's org, runs the privileged
;; DNS-TXT lookup, flips `:verified?`; it throws :authz/forbidden /
;; :domain/unverified for a bad caller / failed proof.
(defbase invoke-verify-domain
  [hostname]
  (when-let [seam (:verify-domain ctx)]
    (seam ctx hostname)))


(def impls
  {:invoke-set-org-handler invoke-set-org-handler
   :invoke-verify-domain invoke-verify-domain})
