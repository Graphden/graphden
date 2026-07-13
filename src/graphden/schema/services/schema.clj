(ns graphden.schema.services.schema
  "Service-registry schema — `:service` entity + `:restart-policy` /
   `:cardinality` enums.

   A `:service` is a declaration: 'keep THIS fn running'. Unlike
   `:fn-execution` (event-shaped, one row per invocation), a service
   is a desired-state row that the reconciler watches and turns into
   a long-running future. Editing the fn graph affects the next
   restart — services point at `:fn-id` (logical) rather than
   `:fn-version-id` (frozen snapshot) for exactly this reason; the
   `:fn-execution` rows the service SPAWNS still record version-id
   for audit-correct event history.

   Service args: there is NO separate args table. The fn pointed at
   by `:fn-id` must have no START-BLOCKING free arguments — every slot
   the fn needs to compute/configure itself at start must be bound in
   the fn-graph (via fn-defs / bindings). To run the same impl with
   different parameters (e.g. web-server on a different port), create a
   derived fn-def that binds the slot differently:

       {:name :web-server-9001 :parent :http-server
        :args {:handler :_app-ring-response :port 9001}}

   …then declare a :service for `:web-server-9001`. This keeps
   service config visible in the graph (versioned, type-checked,
   composable) and avoids duplicating the binding mechanism.

   NOTE 'start-blocking' is NARROWER than 'any free arg'. A listener's
   handler (an `:http-server` `:handler`, a `:schedule` body) is a
   callback the deferred invoker runs per request/tick — its own free
   args are per-invocation and DON'T block starting the service. So
   `web-server` (whose handler is the whole editor+API router, with ~45
   free args deep in that per-request tree) IS service-able even though
   `free-arg-slot-map` reports those 45. The rule blocks only DIRECT
   free args + args lifted through DATA slots (a genuinely unstartable
   fn — `add` with no operand, a cron missing `:cron`).

   Enforced at service-create time by the graph guard
   `:_create-service-free-args-rej` (in `web/crud/fns.edn`), which
   rejects the create when `:service-blocking-free-args` (the
   service-ability projection of `:free-arg-slot-map` — drops the
   callback subtrees) reports the target fn still has an unbound
   start-blocking slot.

   NOT versioned. Services mutate in place — when the admin toggles
   `:enabled?` or changes `:restart-policy`, the new value is the
   only truth. The audit trail for what actually ran lives in
   `:fn-execution` rows (each carries `:fn-version-id` of the graph
   it executed); per-service edit history would only re-record admin
   intent and is intentionally absent from
   `versioning.storage.resolution/entity-config`.

   Future evolution:
   - `:service-schedule` — 1-to-many child for cron / interval triggers.
     Service without schedule rows = continuous (default).

   Restart-policy semantics:
   - `:always` — restart on any exit (crash OR normal return).
   - `:on-failure` — restart only on uncaught exception.
   - `:never` — single-shot, log on exit.

   Cardinality semantics — how many pods run the service at once:
   - `:singleton` — exactly one pod cluster-wide. The reconciler gates
     the start on a Postgres advisory lock; losers idle. This is what
     a cron / `:schedule` loop needs: running it on every pod would
     fire the job N times per tick.
   - `:per-pod` — every pod runs its own copy, no lock. This is what a
     listener like `:http-server` needs: N pods behind a load balancer
     must each bind their port. Gating a listener on a cluster-wide
     lock means only ONE pod ever serves HTTP.
   - `:pool` — up to `:pool-size` N pods run it at once (exactly N when
     the fleet has ≥ N pods; fewer pods ⇒ one copy each). It generalises
     `:singleton` (which is a pool of 1): the reconciler races for one of
     N advisory-lock SLOTS (keys `msb+0 … msb+(N-1)`), holding the first
     free slot; when all N slots are held by siblings the pod idles. Use
     it for a background worker that should be redundant/parallel across a
     bounded number of pods without fanning out to the whole fleet. A
     `:pool` row with a nil / non-positive `:pool-size` degrades to a
     singleton (safe — one copy, not a fan-out). NOTE: the pool SIZE is
     fixed; load-driven autoscaling of N is intentionally out of scope
     (that would need a per-service load signal + a scaling controller —
     the request path scales via cells + HPA instead).

   nil ≡ `:singleton` for rows that pre-date the field (the reconciler
   reads it through `service-cardinality`), so an un-migrated row keeps
   its old lock-gated behaviour rather than silently fanning out."
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
;; Enum :cardinality
;; =============================================================================

(def ^:private cardinality-enum-uuid
  #uuid "a5d334b5-f812-4c3e-9a92-b01c3ffa0138")


(def ^:private cardinality-values
  (array-map
    :singleton #uuid "df3c1776-17de-456d-b9e9-12a46543d18e"
    :per-pod   #uuid "f7f7154a-0208-475a-9c99-200b10e609ba"
    :pool      #uuid "c7a4e0b2-9d61-4f3a-8b2c-1e5f7a0d6c93"))


(defn- cardinality-enum-values
  []
  (mapv (fn [[k uuid]] {:uuid uuid :value k}) cardinality-values))


(def default-cardinality
  "Cardinality assumed for a `:service` row whose `:cardinality` is nil
   — rows written before the field existed. Singleton is the safe
   default: it preserves the pre-field lock-gated behaviour. Fanning a
   legacy cron row out to every pod would multiply its side-effects."
  :singleton)


(defn service-cardinality
  "A service row's effective cardinality. The ONE place that resolves
   nil → the default, so callers never re-derive it."
  [svc]
  (or (:cardinality svc) default-cardinality))


(defn singleton?
  "True when this service must run on exactly one pod cluster-wide,
   i.e. its start has to be gated on the advisory lock."
  [svc]
  (= :singleton (service-cardinality svc)))


(defn effective-pool-size
  "How many pods may run this service at once — the advisory-lock SLOT
   count. `:per-pod` → nil (unbounded, no lock). `:singleton` → 1.
   `:pool` → its `:pool-size`, degrading a nil / non-positive size to 1
   (safe: behaves as a singleton rather than fanning out). This is the
   ONE place that resolves the cardinality → slot count, so the reconciler
   never re-derives it."
  [svc]
  (case (service-cardinality svc)
    :per-pod nil
    :singleton 1
    :pool (let [n (:pool-size svc)]
            (if (and (integer? n) (pos? n)) n 1))
    ;; unknown cardinality — treat conservatively as a singleton
    1))


(defn lock-gated?
  "True when this service's start must race for one of its advisory-lock
   slots (`:singleton` or `:pool`). `:per-pod` runs on every pod without a
   lock, so it is never gated."
  [svc]
  (some? (effective-pool-size svc)))


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


;; How many pods run this service at once. Nullable so the column can
;; be added to a live table without a backfill migration; the seeder
;; fills nil rows from their package declaration, and
;; `service-cardinality` reads nil as `:singleton`.
(def ^:private service-cardinality-field-uuid
  #uuid "5ba1c395-2012-43d0-897d-8beae71344cf")


;; How many pods run this service when `:cardinality` is `:pool`. Nullable
;; (only meaningful for `:pool`; nil / non-positive degrades to 1 via
;; `effective-pool-size`). Adding it needs no backfill — legacy rows read
;; nil, and only `:pool` rows consult it.
(def ^:private service-pool-size-field-uuid
  #uuid "a1f9d3c7-6b28-4e05-9c14-3d7e8f2b0a56")


;; Per-branch service binding. The same fn-id can have a different
;; `:enabled?` / `:restart-policy` row on each branch, so `:my-server`
;; keeps running on `dev` while staying disabled on `main`. The
;; reconciler groups services by this field and runs each branch in its
;; own ExecutionContext (per `branch-router/get-or-create-context!`).
(def ^:private service-branch-id-field-uuid
  #uuid "c3a8d572-1e4f-4b06-9a25-6f8c4e9d5a31")


;; =============================================================================
;; Schema
;; =============================================================================

(defn extend-builder
  "Extend a schema builder with the :service entity + the
   :restart-policy / :cardinality enums. Chain after
   `versioned.schema/extend-builder` (refs `:fn` which the graph schema
   registers; non-versioned so the versioned-storage decorator passes
   writes straight through to the base storage, same as
   `:fn-execution`)."
  [builder]
  (-> builder
      (ds/add-enum :restart-policy
                   restart-policy-enum-uuid
                   (restart-policy-enum-values))

      (ds/add-enum :cardinality
                   cardinality-enum-uuid
                   (cardinality-enum-values))

      (ds/add-entity :service service-entity-uuid
                     {:fn-id {:uuid service-fn-id-field-uuid
                              :type :ref
                              :ref-entity :fn}
                      :enabled? {:uuid service-enabled-field-uuid
                                 :type :bool}
                      :restart-policy {:uuid service-restart-policy-field-uuid
                                       :type :enum
                                       :enum-name :restart-policy}
                      :cardinality {:uuid service-cardinality-field-uuid
                                    :type :enum
                                    :enum-name :cardinality
                                    :nullable? true}
                      :pool-size {:uuid service-pool-size-field-uuid
                                  :type :int
                                  :nullable? true}
                      :branch-id {:uuid service-branch-id-field-uuid
                                  :type :ref
                                  :ref-entity :branch
                                  :nullable? true}})))
