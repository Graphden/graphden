(ns graphden.tenancy.authz
  "Per-target-namespace write enforcement (PLATFORM_PLAN §4.2 refinement).

   The coarse request-scope gate can only check the org (the request body —
   hence the target namespace — isn't available there). This layer runs at
   the STORAGE level, where the entity being written carries its
   `:namespace-id`: it resolves the namespace path and checks the current
   principal's grant against it. A denied write throws `:authz/forbidden`,
   which the request-scope wrap maps to a 403.

   Scoped to `:fn` writes that set a namespace — the primary namespaced
   write. Other entities (binding / slot, namespaced via their fn) and
   updates that don't touch `:namespace-id` fall back to the coarse org gate
   + RLS; tightening those is a documented follow-up."
  (:require
    [clojure.string :as str]
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


(defn writable?
  "May `principal` write the namespace `ns-id` resolves to, per grant
   `store`? nil principal / `:user` → denied."
  [store storage principal ns-id]
  (boolean (when-let [user (:user principal)]
             (grant/can? store user :write (namespace-path storage ns-id)))))


(defn authorize-writer
  "Build a write guard for OrgScopedStorage closing over the grant `store`
   and `storage` (to resolve namespaces). Returns `(fn [entity-name data])`
   that throws `:authz/forbidden` when a `:fn` write sets a `:namespace-id`
   the current principal (`tc/*current-principal*`) lacks `:write` on.
   Anything else passes."
  [store storage]
  (fn [entity-name data]
    (when (and (not= (tc/current-org) tc/public-org) ; platform / admin: unrestricted
               (= entity-name :fn)
               (contains? data :namespace-id))
      (when-not (writable? store storage tc/*current-principal* (:namespace-id data))
        (throw (ex-info "forbidden: no :write grant on the target namespace"
                        {:type :authz/forbidden
                         :namespace-id (:namespace-id data)}))))))
