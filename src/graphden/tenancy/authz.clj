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
      (when-not (and (:user tc/*current-principal*)
                     (grant/can? store (:user tc/*current-principal*)
                                 cap (namespace-path storage ns-id)))
        (throw (ex-info (str "forbidden: no :" (name cap) " grant on the target namespace")
                        {:type :authz/forbidden :fn-id fn-id :namespace-id ns-id}))))))


(defn authorize-writer
  "Build a write guard for OrgScopedStorage closing over the grant `store`
   and `storage` (to resolve namespaces). Returns `(fn [entity-name data id])`
   that throws `:authz/forbidden` for a tenant write the principal isn't
   authorized for. Platform / admin (public org) is unrestricted.

   - `:fn` that sets `:namespace-id` → needs `:write` on it.
   - `:binding` / `:binding-list-item` → needs `:write` on the owning fn's
     namespace. (§4.3's narrower `:bind-args` / `:append-list` nuance is layered
     on in a follow-up.)"
  [store storage]
  (fn [entity-name data id]
    (when (not= (tc/current-org) tc/public-org) ; platform / admin: unrestricted
      (case entity-name
        :fn (when (contains? data :namespace-id)
              (when-not (writable? store storage tc/*current-principal* (:namespace-id data))
                (throw (ex-info "forbidden: no :write grant on the target namespace"
                                {:type :authz/forbidden :namespace-id (:namespace-id data)}))))
        :binding (deny-write! store storage :write (binding-owner-fn-id storage data id))
        :binding-list-item (deny-write! store storage :write (list-item-owner-fn-id storage data id))
        nil))))


(defn executable?
  "May `principal` execute the fn whose `:namespace-id` is `ns-id`, per grant
   `store`? Resolves the fn's namespace path and checks `:execute`."
  [store storage principal ns-id]
  (boolean (when-let [user (:user principal)]
             (grant/can? store user :execute (namespace-path storage ns-id)))))


(defn authorize-executor
  "Build an execute guard `(fn [ctx fn-id])` for the executor's `:execute-
   guard` seam: it throws `:authz/forbidden` when the current principal lacks
   `:execute` on the fn's namespace. Skips platform / admin (public org) and
   system execution (no principal) — services / cron run unrestricted."
  [store]
  (fn [ctx fn-id]
    (when (and (not= (tc/current-org) tc/public-org)
               tc/*current-principal*)
      (let [storage (:storage ctx)
            ns-id (:namespace-id (sp/read-entity storage :fn fn-id))]
        (when-not (executable? store storage tc/*current-principal* ns-id)
          (throw (ex-info "forbidden: no :execute grant on the fn's namespace"
                          {:type :authz/forbidden :fn-id fn-id})))))))
