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
    [graphden.executor.defbase :refer [defbase]]
    [graphden.tenancy.context :as tc]))


(defbase invalidate-byo-cache
  "Drop the byo execution-mode memo for org `name` so a mode flip takes
   effect at once instead of within the ~5s TTL. One interface call."
  [name]
  (tc/invalidate-byo-cache! name))


(def impls
  {:invalidate-byo-cache invalidate-byo-cache})
