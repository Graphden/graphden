(ns graphden.schema.services.schema
  "Service-registry schema — `:service` entity + `:restart-policy` enum.

   A `:service` is a declaration: 'keep THIS fn running'. Unlike
   `:fn-execution` (event-shaped, one row per invocation), a service
   is a desired-state row that the reconciler watches and turns into
   a long-running future. Editing the fn graph affects the next
   restart — services point at `:fn-id` (logical) rather than
   `:fn-version-id` (frozen snapshot) for exactly this reason; the
   `:fn-execution` rows the service SPAWNS still record version-id
   for audit-correct event history.

   Service args: there is NO separate args table. The fn pointed at
   by `:fn-id` MUST have zero free arguments — every slot must be
   bound in the fn-graph itself (via fn-defs / bindings). To run the
   same impl with different parameters (e.g. web-server on a
   different port), create a derived fn-def that binds the slot
   differently:

       {:name :web-server-9001 :parent :http-server
        :args {:handler :_app-ring-response :port 9001}}

   …then declare a :service for `:web-server-9001`. This keeps
   service config visible in the graph (versioned, type-checked,
   composable) and avoids duplicating the binding mechanism.

   `validate-execute` enforces 'no free args' at service-create time
   — see `crud.fn-execution/validate-service-row` (callsite TBD; for
   now enforced inside the apply-create path).

   NOT versioned. Services mutate in place — when the admin toggles
   `:enabled?` or changes `:restart-policy`, the new value is the
   only truth. The audit trail for what actually ran lives in
   `:fn-execution` rows (each carries `:fn-version-id` of the graph
   it executed); per-service edit history would only re-record admin
   intent and is intentionally absent from
   `versioning.storage.resolution/entity-config`.

   Future evolution (NOT in Phase 1):
   - `:service-schedule` — 1-to-many child for cron / interval triggers.
     Service without schedule rows = continuous (default).
   - `:owner-pod-id` field on `:service` for multi-pod leader election.

   Restart-policy semantics (Phase 1):
   - `:always` — restart on any exit (crash OR normal return).
   - `:on-failure` — restart only on uncaught exception.
   - `:never` — single-shot, log on exit."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


;; =============================================================================
;; Enum :restart-policy
;; =============================================================================

(def ^:private restart-policy-enum-uuid
  #uuid "1c3bcf71-fc30-48a0-8f17-ae420c7f3827")


(def ^:private restart-policy-values
  (array-map
    :always     #uuid "32a42d5c-4f22-4275-b0c5-bd499be68929"
    :on-failure #uuid "d05ed4e9-04e7-4bf3-bc3b-faf26ea0c76f"
    :never      #uuid "8f46534f-1179-406f-86c0-ccb10834603a"))


(defn- restart-policy-enum-values
  []
  (mapv (fn [[k uuid]] {:uuid uuid :value k}) restart-policy-values))


;; =============================================================================
;; Entity UUID
;; =============================================================================

(def ^:private service-entity-uuid
  #uuid "9495bba8-2c6c-444f-be00-be1f4ad7ee1b")


;; =============================================================================
;; Field UUIDs — :service
;; =============================================================================

(def ^:private service-fn-id-field-uuid
  #uuid "5f7802a6-44d2-4f52-9a51-719d4e4d5d62")


(def ^:private service-enabled-field-uuid
  #uuid "d9cfda89-4192-4e10-b21d-6257659a4c00")


(def ^:private service-restart-policy-field-uuid
  #uuid "e078766c-9f02-40f5-bdc0-fa8ed95ef944")


;; Per-branch service binding (Phase 2 — branch-local services).
;; Same fn-id can have a different `:enabled?` / `:restart-policy`
;; row on each branch, so `:my-server` keeps running on `dev` while
;; staying disabled on `main`. Reconciler groups services by this
;; field and runs each branch in its own ExecutionContext (per the
;; existing `branch-router/get-or-create-context!`). Backfilled to
;; the main branch's id on first sync for legacy rows.
(def ^:private service-branch-id-field-uuid
  #uuid "c3a8d572-1e4f-4b06-9a25-6f8c4e9d5a31")


;; =============================================================================
;; Schema
;; =============================================================================

(defn extend-builder
  "Extend a schema builder with the :service entity + :restart-policy
   enum. Chain after `versioned.schema/extend-builder` (refs `:fn`
   which the graph schema registers; non-versioned so the versioned-
   storage decorator passes writes straight through to the base
   storage, same as `:fn-execution`)."
  [builder]
  (-> builder
      (ds/add-enum :restart-policy
                   restart-policy-enum-uuid
                   (restart-policy-enum-values))

      (ds/add-entity :service service-entity-uuid
                     {:fn-id {:uuid service-fn-id-field-uuid
                              :type :ref
                              :ref-entity :fn}
                      :enabled? {:uuid service-enabled-field-uuid
                                 :type :bool}
                      :restart-policy {:uuid service-restart-policy-field-uuid
                                       :type :enum
                                       :enum-name :restart-policy}
                      :branch-id {:uuid service-branch-id-field-uuid
                                  :type :ref
                                  :ref-entity :branch
                                  :nullable? true}})))
