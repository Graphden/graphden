(ns graphden.tenancy.authz
  "Per-target-namespace write enforcement (PLATFORM_PLAN §4.2 refinement).

   The coarse request-scope gate can only check the org (the request body —
   hence the target namespace — isn't available there). This layer runs at
   the STORAGE level, where the entity being written carries its
   `:namespace-id`: it resolves the namespace path and checks the current
   principal's grant against it. A denied write throws `:authz/forbidden`,
   which the request-scope wrap maps to a 403.

   Covers `:fn` writes that set a namespace, plus `:binding` and
   `:binding-list-item` writes/deletes (namespaced via their owning fn — §4.3).
   The id is threaded in so a value-only binding update or a delete (whose
   `data` lacks `:fn-id`) still resolves its namespace by reading the row."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [graphden.crud.entities :as entities]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.grant :as grant]))


(defn namespace-path
  "Dot-path of namespace `ns-id`, built by walking the `:ns` parent chain
   (`:name` + `:parent-id`). nil / unresolvable `ns-id` → \"\" (root)."
  [storage ns-id]
  (loop [id ns-id
         segments ()]
    (if-let [row (and id (sp/read-entity storage :ns id))]
      (recur (:parent-id row) (conj segments (:name row)))
      (str/join "." segments))))


(defn view-impl-filter
  "The `graphden.crud.entities/view-impl-filter` seam impl the addon installs:
   strip the internal composition (parent-ids + bindings) of fns the current
   viewer lacks `:view-impl` on from a `/api/graph/entities` dump. A fn is
   shown IN FULL when it is OWNED by the viewer's org (its `:org-id` = the
   current org — you see your own org's internals) OR the viewer holds
   `:view-impl` (or a stronger cap, via `cap-implies?`) on its namespace;
   otherwise its composition is concealed — public / shared fns whose
   internals the author didn't grant.

   Two whole-dump pass-throughs: the PLATFORM context (`current-org` = the
   public org — the operator/admin, never a tenant) sees everything, and a
   dump with no `:fns` (`:tree` scope) has nothing to hide. Runs in the
   platform handler ctx on the tenant's behalf; `store` is the base grant
   store, `request-store` picks up the per-request memo."
  [store storage graph]
  (let [org (tc/current-org)]
    (if (or (not (:fns graph)) (= org tc/public-org))
      graph
      (let [subj   (grant/subject tc/*current-principal*)
            rstore (grant/request-store store)
            hidden (into #{}
                         (comp
                           (remove (fn [f]
                                     (or (= (:org-id f) org)
                                         (and subj rstore
                                              (grant/can? rstore subj :view-impl
                                                          (namespace-path storage (:namespace-id f)))))))
                           (map :id))
                         (:fns graph))]
        (entities/strip-impl-of graph hidden)))))


(defn install-view-impl-filter!
  "Install `view-impl-filter` into the `crud.entities` graph-read seam, closed
   over the base grant `store` (may be nil — then only own-org fns show their
   internals) and `storage`. Called by the addon at init so
   `/api/graph/entities` conceals non-owned fns' internals for tenants."
  [store storage]
  (reset! entities/view-impl-filter (partial view-impl-filter store storage)))


(defn uninstall-view-impl-filter!
  "Clear the view-impl seam (→ everything visible). Called on tenancy-system
   halt so the process-global filter is lifecycle-bound and can't leak a stale
   storage into a later test in the same JVM."
  []
  (reset! entities/view-impl-filter nil))


(defn writable?
  "May `principal` write the namespace `ns-id` resolves to, per grant
   `store`? nil principal / `:user` → denied."
  [store storage principal ns-id]
  (boolean (when-let [subj (grant/subject principal)]
             (grant/can? store subj :write (namespace-path storage ns-id)))))


(defn- binding-owner-fn-id
  "The fn-id a `:binding` write targets — `:fn-id` in `data` (create) or read
   from the existing binding row by `id` (value-only update / delete)."
  [storage data id]
  (or (:fn-id data)
      (some->> id (sp/read-entity storage :binding) :fn-id)))


(defn- list-item-owner-fn-id
  "The fn-id a `:binding-list-item` write targets — resolved through its
   binding (`:binding-id` in `data` on create, or read from the item row by
   `id` on update / delete)."
  [storage data id]
  (let [bid (or (:binding-id data)
                (some->> id (sp/read-entity storage :binding-list-item) :binding-id))]
    (:fn-id (some->> bid (sp/read-entity storage :binding)))))


(defn deny-write!
  "Throw `:authz/forbidden` unless the current principal holds `cap` (or
   stronger, via `grant/cap-implies?`) on the namespace `fn-id` lives in. No-op
   when `fn-id` is unresolvable (the write targets nothing — storage rejects it)
   or when the principal is authorized. `cap` lets callers require a narrower
   §4.3 capability (`:bind-args` / `:append-list`) instead of `:write`."
  [store storage cap fn-id]
  (when fn-id
    (let [ns-id (:namespace-id (sp/read-entity storage :fn fn-id))]
      (when-not (when-let [subj (grant/subject tc/*current-principal*)]
                  (grant/can? store subj cap (namespace-path storage ns-id)))
        (throw (ex-info (str "forbidden: no :" (name cap) " grant on the target namespace")
                        {:type :authz/forbidden :fn-id fn-id :namespace-id ns-id}))))))


(def ^:private binding-structural-keys
  "Binding fields a `:bind-args` edit may NOT touch — anything beyond the value
   (`:value` / `:value-present`) restructures the binding and needs `:write`.
   Includes the identity fields AND the behaviour-changing overlays:
   `:rename-to` (renames a free-arg), `:terminal` (caps the chain),
   `:list-append` / `:list-closed` (grow / seal a sequence). Missing any of
   these lets a `:bind-args`-only holder restructure a binding it may only
   re-value — e.g. seal a slot against descendants."
  #{:ref-fn-id :type-override-fn-id :slot-id :fn-id
    :rename-to :terminal :list-append :list-closed})


(defn- value-only-binding-delta?
  "True when `data` (the changed binding keys) touches ONLY value fields, so a
   `:bind-args` holder may apply it. A CREATE always carries `:fn-id`/`:slot-id`
   and a DELETE has nil data — both correctly fall through to requiring `:write`."
  [data]
  (boolean (and (seq data)
                (empty? (set/intersection (set (keys data)) binding-structural-keys)))))


(defn authorize-writer
  "Build a write guard for OrgScopedStorage closing over the grant `store`
   and `storage` (to resolve namespaces). Returns `(fn [entity-name data id])`
   that throws `:authz/forbidden` for a tenant write the principal isn't
   authorized for. Platform / admin (public org) is unrestricted.

   §4.3 restricted editing — the required capability narrows by the edit:
   - `:fn` that sets `:namespace-id` → `:write` on it.
   - `:binding` → `:bind-args` when the delta touches ONLY `:value` (a value
     tweak); `:write` otherwise (create / restructure / delete).
   - `:binding-list-item` → `:append-list` (add / remove / reorder a list item).
   `:write` and `:admin` subsume the narrow caps (`grant/cap-implies?`)."
  [store storage]
  (fn [entity-name data id]
    (when (not= (tc/current-org) tc/public-org) ; platform / admin: unrestricted
      ;; Pick up the request-scope memo so a batch write shares ONE `:grant`
      ;; query across all its rows instead of one per row.
      (let [store (grant/request-store store)]
        (case entity-name
          :fn (cond
                ;; create / namespace-move → `:write` on the TARGET namespace.
                (contains? data :namespace-id)
                (do
                  (when-not (writable? store storage tc/*current-principal* (:namespace-id data))
                    (throw (ex-info "forbidden: no :write grant on the target namespace"
                                    {:type :authz/forbidden :namespace-id (:namespace-id data)})))
                  ;; A MOVE of an EXISTING fn (id present) also needs `:write`
                  ;; on the fn's CURRENT namespace — otherwise a holder of the
                  ;; target namespace alone could pull a fn out of a namespace
                  ;; they were deliberately not granted. A create (id nil) has
                  ;; no source namespace to check.
                  (when id
                    (deny-write! store storage :write id)))
                ;; delete / structural update (parent-ids, …) of an EXISTING fn —
                ;; the delta carries no `:namespace-id`, so gate on the fn's OWN
                ;; namespace (read by id). Without this a `:bind-args` holder,
                ;; which passes the coarse `can-mutate?` gate, could delete or
                ;; reparent ANY fn in the org, including namespaces it holds no
                ;; grant on. (A create with no namespace — id nil — stays
                ;; ungated: it lands as an org-owned rootless fn.)
                id
                (deny-write! store storage :write id))
          :binding (deny-write! store storage
                                (if (value-only-binding-delta? data) :bind-args :write)
                                (binding-owner-fn-id storage data id))
          :binding-list-item (deny-write! store storage :append-list
                                          (list-item-owner-fn-id storage data id))
          nil)))))


(defn executable?
  "May `principal` execute the fn whose `:namespace-id` is `ns-id`, per grant
   `store`? Resolves the fn's namespace path and checks `:execute`."
  [store storage principal ns-id]
  (boolean (when-let [subj (grant/subject principal)]
             (grant/can? store subj :execute (namespace-path storage ns-id)))))


(defn authorize-executor
  "Build an execute guard `(fn [ctx fn-id])` for the executor's `:execute-
   guard` seam: it throws `:authz/forbidden` when the current principal lacks
   `:execute` on the fn's namespace. Skips platform / admin (public org) and
   system execution (no principal) — services / cron run unrestricted."
  [store]
  (fn [ctx fn-id]
    (when (and (not= (tc/current-org) tc/public-org)
               tc/*current-principal*)
      (let [store (grant/request-store store)
            storage (:storage ctx)
            ns-id (:namespace-id (sp/read-entity storage :fn fn-id))]
        (when-not (executable? store storage tc/*current-principal* ns-id)
          (throw (ex-info "forbidden: no :execute grant on the fn's namespace"
                          {:type :authz/forbidden :fn-id fn-id})))))))
