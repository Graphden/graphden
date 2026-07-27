(ns graphden.tenancy.plan
  "Per-org PLAN / tier (task #4). A tenant org carries a `:plan` slug (nil =
   the locked free tier); this resolves it to the effect allow-list its
   submitted graph runs under. It is the config backbone the safe-egress (#5)
   and process (#6) tiers hang their quota ceilings off — for now it gates the
   effect vocabulary (which capabilities a tenant's graph may reach), the
   'couple of buttons' upgrade being a change to the org's `:plan` row.

   Installed into `compile-runtime/cloud-allowed-effects-resolver` by the
   addon; `graphden.crud.fn-execution` calls it per execute to set the exec
   ctx's `:allowed-effects` (only for a real tenant org — the platform ctx
   stays unrestricted)."
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]))


(def plans
  "Plan slug → the effect allow-list a tenant on that plan may reach. `free`
   is the locked default (`#{:db :state :time :random}`, unchanged behaviour);
   `network` additionally allows outbound `:network` (external HTTP / SQL,
   itself guarded by the egress broker — #5). Extend here as tiers are added
   (e.g. a `:process` service tier — #6)."
  {"free"    cr/default-cloud-allowed-effects
   "network" (conj cr/default-cloud-allowed-effects :network)})


(defn allowed-effects-for
  "The effect allow-list for tenant `org`, read from its `:plan` via the
   platform `storage` (`:org` is tenant-forbidden, so this runs in the platform
   ctx on the tenant's behalf). Unknown / nil plan, or the public org → the
   locked free tier."
  [storage org]
  (or (when (and org (not= org tc/public-org))
        (get plans (:plan (first (sp/query-entities storage :org {:name org})))))
      cr/default-cloud-allowed-effects))


(defn install!
  "Install `allowed-effects-for` (closed over the platform `storage`) into the
   compile-runtime seam, so a tenant's submitted graph runs under its plan's
   effect allow-list instead of the fixed default."
  [storage]
  (reset! cr/cloud-allowed-effects-resolver (partial allowed-effects-for storage)))


(defn uninstall!
  "Clear the resolver seam (→ the locked default). Called on tenancy-system
   halt so the process-global resolver is lifecycle-bound and can't leak a
   stale storage into a later test in the same JVM."
  []
  (reset! cr/cloud-allowed-effects-resolver nil))
